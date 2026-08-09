//! Per-batch serialization for the durable disaster-restore journal.
//!
//! The registry keeps only weak references. A [`RestoreLockLease`] owns the strong reference for
//! exactly one holder or waiter, then removes the matching weak entry when the final lease leaves.
//! This lets untrusted batch UUIDs serialize concurrent requests without becoming durable process
//! state, and removal cannot split a waiter onto a second mutex.

use std::collections::HashMap;
use std::sync::{Arc, Mutex as RegistryMutex, MutexGuard as RegistryGuard, Weak};

#[cfg(test)]
use tokio::sync::MutexGuard;
use tokio::sync::{Mutex, OwnedMutexGuard};

use crate::ApiError;

#[derive(Clone, Default)]
pub(crate) struct RestoreLockPool {
    entries: Arc<RegistryMutex<HashMap<String, Weak<Mutex<()>>>>>,
}

impl RestoreLockPool {
    pub(crate) fn acquire(&self, key: String) -> RestoreLockLease {
        let mut entries = lock_registry(&self.entries);
        let lock = entries
            .get(&key)
            .and_then(Weak::upgrade)
            .unwrap_or_else(|| {
                let lock = Arc::new(Mutex::new(()));
                entries.insert(key.clone(), Arc::downgrade(&lock));
                lock
            });
        RestoreLockLease {
            key,
            lock,
            entries: self.entries.clone(),
        }
    }

    pub(crate) async fn run_serialized<T, F>(
        &self,
        key: String,
        operation: F,
    ) -> Result<T, ApiError>
    where
        T: Send + 'static,
        F: FnOnce() -> Result<T, ApiError> + Send + 'static,
    {
        let lease = self.acquire(key);
        let guard = lease.lock.clone().lock_owned().await;
        let scope = RestoreLockScope {
            guard: Some(guard),
            _lease: lease,
        };
        crate::run_blocking(move || {
            let _scope = scope;
            operation()
        })
        .await
    }

    #[cfg(test)]
    fn entry_count(&self) -> usize {
        lock_registry(&self.entries).len()
    }
}

struct RestoreLockScope {
    guard: Option<OwnedMutexGuard<()>>,
    _lease: RestoreLockLease,
}

impl Drop for RestoreLockScope {
    fn drop(&mut self) {
        drop(self.guard.take());
    }
}

pub(crate) struct RestoreLockLease {
    key: String,
    lock: Arc<Mutex<()>>,
    entries: Arc<RegistryMutex<HashMap<String, Weak<Mutex<()>>>>>,
}

impl RestoreLockLease {
    #[cfg(test)]
    pub(crate) async fn lock(&self) -> MutexGuard<'_, ()> {
        self.lock.lock().await
    }
}

impl Drop for RestoreLockLease {
    fn drop(&mut self) {
        let mut entries = lock_registry(&self.entries);
        let own_weak = Arc::downgrade(&self.lock);
        let is_last_lease = Arc::strong_count(&self.lock) == 1;
        let is_current_entry = entries
            .get(&self.key)
            .is_some_and(|stored| Weak::ptr_eq(stored, &own_weak));
        if is_last_lease && is_current_entry {
            entries.remove(&self.key);
        }
    }
}

fn lock_registry<T>(mutex: &RegistryMutex<T>) -> RegistryGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::RestoreLockPool;
    use crate::ApiError;
    use std::sync::mpsc;
    use std::sync::Arc;
    use std::time::Duration;
    use tokio::sync::{oneshot, Barrier};
    use uuid::Uuid;

    #[tokio::test]
    async fn random_valid_batch_uuids_do_not_accumulate() {
        let pool = RestoreLockPool::default();

        for key in (1..=100_000).map(|index| Uuid::from_u128(index).to_string()) {
            let lease = pool.acquire(key);
            let guard = lease.lock().await;
            drop(guard);
            drop(lease);
        }

        assert_eq!(pool.entry_count(), 0);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn same_batch_serializes_while_different_batches_run_in_parallel() {
        let pool = RestoreLockPool::default();
        let first = pool.acquire("same".to_owned());
        let first_guard = first.lock().await;
        let start = Arc::new(Barrier::new(3));

        let same = pool.acquire("same".to_owned());
        let same_start = start.clone();
        let (same_attempting_tx, same_attempting_rx) = oneshot::channel();
        let (same_acquired_tx, mut same_acquired_rx) = oneshot::channel();
        let same_task = tokio::spawn(async move {
            same_start.wait().await;
            same_attempting_tx.send(()).unwrap();
            let _guard = same.lock().await;
            same_acquired_tx.send(()).unwrap();
        });

        let different = pool.acquire("different".to_owned());
        let different_start = start.clone();
        let (different_acquired_tx, different_acquired_rx) = oneshot::channel();
        let different_task = tokio::spawn(async move {
            different_start.wait().await;
            let _guard = different.lock().await;
            different_acquired_tx.send(()).unwrap();
        });

        start.wait().await;
        same_attempting_rx.await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), different_acquired_rx)
            .await
            .expect("different batch should not wait")
            .unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(50), &mut same_acquired_rx)
                .await
                .is_err(),
            "same batch crossed the held lock",
        );

        drop(first_guard);
        drop(first);
        tokio::time::timeout(Duration::from_secs(1), &mut same_acquired_rx)
            .await
            .expect("same batch should resume after release")
            .unwrap();
        same_task.await.unwrap();
        different_task.await.unwrap();
        assert_eq!(pool.entry_count(), 0);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn final_waiter_keeps_one_lock_identity_until_it_releases() {
        let pool = RestoreLockPool::default();
        let first = pool.acquire("batch".to_owned());
        let first_guard = first.lock().await;

        let waiter = pool.acquire("batch".to_owned());
        let (waiter_attempting_tx, waiter_attempting_rx) = oneshot::channel();
        let (waiter_acquired_tx, waiter_acquired_rx) = oneshot::channel();
        let (release_waiter_tx, release_waiter_rx) = oneshot::channel();
        let waiter_task = tokio::spawn(async move {
            waiter_attempting_tx.send(()).unwrap();
            let _guard = waiter.lock().await;
            waiter_acquired_tx.send(()).unwrap();
            release_waiter_rx.await.unwrap();
        });
        waiter_attempting_rx.await.unwrap();

        drop(first_guard);
        drop(first);
        tokio::time::timeout(Duration::from_secs(1), waiter_acquired_rx)
            .await
            .expect("waiter should become holder")
            .unwrap();

        let follower = pool.acquire("batch".to_owned());
        let (follower_attempting_tx, follower_attempting_rx) = oneshot::channel();
        let (follower_acquired_tx, mut follower_acquired_rx) = oneshot::channel();
        let follower_task = tokio::spawn(async move {
            follower_attempting_tx.send(()).unwrap();
            let _guard = follower.lock().await;
            follower_acquired_tx.send(()).unwrap();
        });
        follower_attempting_rx.await.unwrap();
        assert_eq!(pool.entry_count(), 1);
        assert!(
            tokio::time::timeout(Duration::from_millis(50), &mut follower_acquired_rx)
                .await
                .is_err(),
            "eviction split a live waiter onto a second lock",
        );

        release_waiter_tx.send(()).unwrap();
        waiter_task.await.unwrap();
        tokio::time::timeout(Duration::from_secs(1), &mut follower_acquired_rx)
            .await
            .expect("follower should resume after final waiter")
            .unwrap();
        follower_task.await.unwrap();
        assert_eq!(pool.entry_count(), 0);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn cancelling_handler_does_not_release_a_running_blocking_operation() {
        let pool = RestoreLockPool::default();
        let first_pool = pool.clone();
        let (first_entered_tx, first_entered_rx) = oneshot::channel();
        let (release_first_tx, release_first_rx) = mpsc::channel();
        let first = tokio::spawn(async move {
            first_pool
                .run_serialized("batch".to_owned(), move || {
                    first_entered_tx.send(()).unwrap();
                    release_first_rx.recv().unwrap();
                    Ok::<_, ApiError>(())
                })
                .await
        });
        first_entered_rx.await.unwrap();
        first.abort();
        let _ = first.await;

        let follower_pool = pool.clone();
        let (follower_attempting_tx, follower_attempting_rx) = oneshot::channel();
        let (follower_entered_tx, mut follower_entered_rx) = oneshot::channel();
        let follower = tokio::spawn(async move {
            follower_attempting_tx.send(()).unwrap();
            follower_pool
                .run_serialized("batch".to_owned(), move || {
                    follower_entered_tx.send(()).unwrap();
                    Ok::<_, ApiError>(())
                })
                .await
        });
        follower_attempting_rx.await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(50), &mut follower_entered_rx)
                .await
                .is_err(),
            "cancellation released the lock before blocking work completed",
        );

        release_first_tx.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(1), &mut follower_entered_rx)
            .await
            .expect("follower should run after blocking work exits")
            .unwrap();
        follower.await.unwrap().unwrap();
        assert_eq!(pool.entry_count(), 0);
    }
}
