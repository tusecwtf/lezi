//! Opt-in, in-process timing for isolated performance runs. No logs or HTTP API.
//!
//! Only fixed stages, counters and monotonic durations are accepted. The ring
//! retains at most 256 samples; callers must copy snapshots between runs when
//! retaining raw evidence. Production configuration leaves the probe unset.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const SAMPLE_CAPACITY: usize = 256;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum Stage {
    HeartbeatRead,
    PullRead,
    PullEncode,
    CommitDurable,
    CommitPublish,
    MediaStage,
    MediaCommit,
    FamilyCreate,
    RestoreStart,
}

const STAGES: [Stage; 9] = [
    Stage::HeartbeatRead,
    Stage::PullRead,
    Stage::PullEncode,
    Stage::CommitDurable,
    Stage::CommitPublish,
    Stage::MediaStage,
    Stage::MediaCommit,
    Stage::FamilyCreate,
    Stage::RestoreStart,
];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Phase {
    FamilyLockWait,
    FamilyLockHold,
    BlockingQueue,
    BlockingWork,
}

#[derive(Clone, Copy, Debug)]
pub struct Sample {
    pub stage: Stage,
    pub phase: Phase,
    pub elapsed: Duration,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Counters {
    pub submitted: u64,
    pub queued: u64,
    pub running: u64,
    pub awaiting: u64,
    /// The caller dropped its await, which includes timeout/cancellation. This
    /// does not claim that the queued or running blocking closure was stopped.
    pub await_abandoned: u64,
    /// Work exited, including a returned error or panic.
    pub finished: u64,
    pub finished_after_await_abandoned: u64,
    /// Runtime shutdown dropped a queued closure without starting it.
    pub discarded_before_start: u64,
}

#[derive(Clone, Debug)]
pub struct Snapshot {
    pub stages: [(Stage, Counters); 9],
    pub samples: Vec<Sample>,
    pub overwritten_samples: u64,
}

#[derive(Default)]
struct State {
    counters: [Counters; 9],
    samples: VecDeque<Sample>,
    overwritten_samples: u64,
}

impl State {
    fn sample(&mut self, stage: Stage, phase: Phase, elapsed: Duration) {
        if self.samples.len() == SAMPLE_CAPACITY {
            self.samples.pop_front();
            self.overwritten_samples += 1;
        }
        self.samples.push_back(Sample {
            stage,
            phase,
            elapsed,
        });
    }
}

#[derive(Default)]
pub struct PerformanceProbe {
    state: Mutex<State>,
}

impl PerformanceProbe {
    /// An explicit numeric copy; no sampling thread, identifiers, paths, content
    /// or dynamically supplied labels are retained.
    pub fn snapshot(&self) -> Snapshot {
        let state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        Snapshot {
            stages: STAGES.map(|stage| (stage, state.counters[stage as usize])),
            samples: state.samples.iter().copied().collect(),
            overwritten_samples: state.overwritten_samples,
        }
    }

    pub(crate) fn span(self: &Arc<Self>, stage: Stage, phase: Phase) -> Span {
        Span {
            probe: self.clone(),
            stage,
            phase,
            started: Instant::now(),
        }
    }

    pub(crate) fn blocking(self: &Arc<Self>, stage: Stage) -> (Awaiting, Worker) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let counters = &mut state.counters[stage as usize];
        counters.submitted += 1;
        counters.queued += 1;
        counters.awaiting += 1;
        let awaiting = Arc::new(AtomicBool::new(true));
        (
            Awaiting {
                probe: self.clone(),
                stage,
                awaiting: awaiting.clone(),
                returned: false,
            },
            Worker {
                probe: self.clone(),
                stage,
                awaiting,
                queued: Instant::now(),
                started: None,
            },
        )
    }
}

pub(crate) struct Span {
    probe: Arc<PerformanceProbe>,
    stage: Stage,
    phase: Phase,
    started: Instant,
}

impl Drop for Span {
    fn drop(&mut self) {
        self.probe
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .sample(self.stage, self.phase, self.started.elapsed());
    }
}

pub(crate) struct Awaiting {
    probe: Arc<PerformanceProbe>,
    stage: Stage,
    awaiting: Arc<AtomicBool>,
    returned: bool,
}

impl Awaiting {
    pub(crate) fn returned(&mut self) {
        self.returned = true;
    }
}

impl Drop for Awaiting {
    fn drop(&mut self) {
        let mut state = self
            .probe
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        self.awaiting.store(false, Ordering::Release);
        let counters = &mut state.counters[self.stage as usize];
        counters.awaiting -= 1;
        if !self.returned {
            counters.await_abandoned += 1;
        }
    }
}

pub(crate) struct Worker {
    probe: Arc<PerformanceProbe>,
    stage: Stage,
    awaiting: Arc<AtomicBool>,
    queued: Instant,
    started: Option<Instant>,
}

impl Worker {
    pub(crate) fn start(&mut self) {
        let now = Instant::now();
        self.started = Some(now);
        let mut state = self
            .probe
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let counters = &mut state.counters[self.stage as usize];
        counters.queued -= 1;
        counters.running += 1;
        state.sample(
            self.stage,
            Phase::BlockingQueue,
            now.duration_since(self.queued),
        );
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let mut state = self
            .probe
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let counters = &mut state.counters[self.stage as usize];
        if let Some(started) = self.started {
            counters.running -= 1;
            counters.finished += 1;
            if !self.awaiting.load(Ordering::Acquire) {
                counters.finished_after_await_abandoned += 1;
            }
            state.sample(self.stage, Phase::BlockingWork, started.elapsed());
        } else {
            counters.queued -= 1;
            counters.discarded_before_start += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{run_observed, ApiError, ServerConfig};

    fn counters(probe: &PerformanceProbe) -> Counters {
        probe.snapshot().stages[Stage::PullRead as usize].1
    }

    #[test]
    fn default_configuration_is_unobserved_and_sample_memory_is_bounded() {
        assert!(ServerConfig::new("unused").performance_probe.is_none());
        let probe = Arc::new(PerformanceProbe::default());
        for _ in 0..(SAMPLE_CAPACITY + 17) {
            drop(probe.span(Stage::PullRead, Phase::FamilyLockWait));
        }
        let snapshot = probe.snapshot();
        assert_eq!(snapshot.samples.len(), SAMPLE_CAPACITY);
        assert_eq!(snapshot.overwritten_samples, 17);
        assert!(snapshot
            .stages
            .iter()
            .all(|(_, counters)| *counters == Counters::default()));
    }

    #[tokio::test]
    async fn observed_success_and_error_preserve_results_and_finish_counts() {
        let probe = Arc::new(PerformanceProbe::default());
        let value = run_observed(Some(&probe), Stage::PullRead, || Ok(42))
            .await
            .unwrap();
        assert_eq!(value, 42);
        let error = run_observed(Some(&probe), Stage::PullRead, || {
            Err::<(), _>(ApiError::internal("synthetic failure"))
        })
        .await;
        assert!(error.is_err());
        assert_eq!(
            counters(&probe),
            Counters {
                submitted: 2,
                finished: 2,
                ..Counters::default()
            }
        );
        assert_eq!(probe.snapshot().samples.len(), 4);
        assert_eq!(
            run_observed(None, Stage::PullRead, || Ok(43))
                .await
                .unwrap(),
            43
        );
        assert_eq!(counters(&probe).submitted, 2);
    }

    #[tokio::test]
    async fn worker_panic_releases_owned_guard_and_finishes_observation() {
        let probe = Arc::new(PerformanceProbe::default());
        let family_lock = Arc::new(tokio::sync::Mutex::new(()));
        let guard = family_lock.clone().lock_owned().await;
        let result = run_observed(
            Some(&probe),
            Stage::PullRead,
            move || -> Result<(), ApiError> {
                let _guard = guard;
                panic!("synthetic worker panic");
            },
        )
        .await;
        assert!(result.is_err());
        assert!(family_lock.try_lock().is_ok());
        let counts = counters(&probe);
        assert_eq!(counts.finished, 1);
        assert_eq!(counts.running, 0);
        assert_eq!(counts.await_abandoned, 0);
    }

    #[tokio::test]
    async fn abandoned_await_does_not_hide_a_running_worker() {
        let probe = Arc::new(PerformanceProbe::default());
        let task_probe = probe.clone();
        let family_lock = Arc::new(tokio::sync::Mutex::new(()));
        let guard = family_lock.clone().lock_owned().await;
        let (started_tx, started_rx) = tokio::sync::oneshot::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        let task = tokio::spawn(async move {
            run_observed(Some(&task_probe), Stage::PullRead, move || {
                started_tx.send(()).unwrap();
                release_rx.recv_timeout(Duration::from_secs(5)).unwrap();
                Ok(((), guard))
            })
            .await
        });
        started_rx.await.unwrap();
        assert_eq!(counters(&probe).running, 1);
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
        let pending = counters(&probe);
        assert_eq!(pending.await_abandoned, 1);
        assert_eq!(pending.awaiting, 0);
        assert_eq!(pending.running, 1);
        assert_eq!(pending.finished, 0);
        assert!(family_lock.try_lock().is_err());
        release_tx.send(()).unwrap();
        // FIFO barrier with one blocking slot is not available in this runtime;
        // wait for the exact observed worker exit, with a bounded test deadline.
        let watcher = probe.clone();
        tokio::spawn(async move {
            while counters(&watcher).running != 0 {
                tokio::task::yield_now().await;
            }
            done_tx.send(()).unwrap();
        });
        tokio::time::timeout(Duration::from_secs(5), done_rx)
            .await
            .unwrap()
            .unwrap();
        let finished = counters(&probe);
        assert_eq!(finished.finished, 1);
        assert_eq!(finished.finished_after_await_abandoned, 1);
        let _released = tokio::time::timeout(Duration::from_secs(5), family_lock.lock())
            .await
            .unwrap();
    }

    #[test]
    fn queued_worker_remains_visible_after_await_is_abandoned() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .max_blocking_threads(1)
            .build()
            .unwrap();
        runtime.block_on(async {
            let probe = Arc::new(PerformanceProbe::default());
            let (occupied_tx, occupied_rx) = tokio::sync::oneshot::channel();
            let (release_tx, release_rx) = std::sync::mpsc::channel();
            let occupying = tokio::task::spawn_blocking(move || {
                occupied_tx.send(()).unwrap();
                release_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            });
            occupied_rx.await.unwrap();
            let task_probe = probe.clone();
            let family_lock = Arc::new(tokio::sync::Mutex::new(()));
            let guard = family_lock.clone().lock_owned().await;
            let task = tokio::spawn(async move {
                run_observed(Some(&task_probe), Stage::PullRead, move || Ok(((), guard))).await
            });
            tokio::task::yield_now().await;
            assert_eq!(counters(&probe).queued, 1);
            assert_eq!(counters(&probe).running, 0);
            task.abort();
            assert!(task.await.unwrap_err().is_cancelled());
            assert_eq!(counters(&probe).queued, 1);
            assert_eq!(counters(&probe).await_abandoned, 1);
            assert!(family_lock.try_lock().is_err());
            release_tx.send(()).unwrap();
            occupying.await.unwrap();
            tokio::task::spawn_blocking(|| ()).await.unwrap();
            let finished = counters(&probe);
            assert_eq!(finished.queued, 0);
            assert_eq!(finished.running, 0);
            assert_eq!(finished.finished, 1);
            assert_eq!(finished.finished_after_await_abandoned, 1);
            assert_eq!(probe.snapshot().samples.len(), 2);
            assert!(family_lock.try_lock().is_ok());
        });
    }
}
