use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use crate::{DEFAULT_CREATE_RATE_LIMIT, DEFAULT_RATE_LIMIT_WINDOW_SECONDS};

const GLOBAL_RATE_LIMIT_MULTIPLIER: u32 = 10;

#[derive(Clone, Debug)]
pub struct RateLimitConfig {
    pub max_attempts: u32,
    pub window_seconds: i64,
}

impl Default for RateLimitConfig {
    fn default() -> Self {
        Self {
            max_attempts: DEFAULT_CREATE_RATE_LIMIT,
            window_seconds: DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
        }
    }
}

pub(super) struct RateLimiter {
    scoped_max_attempts: u32,
    group_max_attempts: u32,
    window_seconds: i64,
    windows: Mutex<RateLimitWindows>,
}

pub(super) enum RateLimitRejection {
    Scoped,
    Group,
    Unavailable,
}

#[derive(Default)]
struct RateLimitWindows {
    groups: HashMap<String, VecDeque<i64>>,
    scoped: HashMap<(String, String), VecDeque<i64>>,
    last_cleanup: Option<i64>,
}

impl RateLimiter {
    pub(super) fn new(config: RateLimitConfig) -> Self {
        let group_max_attempts = config
            .max_attempts
            .saturating_mul(GLOBAL_RATE_LIMIT_MULTIPLIER);
        Self::new_with_group_limit(config, group_max_attempts)
    }

    pub(super) fn new_with_group_limit(config: RateLimitConfig, group_max_attempts: u32) -> Self {
        Self {
            scoped_max_attempts: config.max_attempts,
            group_max_attempts,
            window_seconds: config.window_seconds,
            windows: Mutex::new(RateLimitWindows::default()),
        }
    }

    pub(super) fn check_and_record(&self, scope: &str, now: i64) -> bool {
        self.check_and_record_in(scope, "__process__", now).is_ok()
    }

    pub(super) fn check_and_record_in(
        &self,
        scope: &str,
        group: &str,
        now: i64,
    ) -> Result<(), RateLimitRejection> {
        let Ok(mut windows) = self.windows.lock() else {
            return Err(RateLimitRejection::Unavailable);
        };

        if windows
            .last_cleanup
            .is_none_or(|last| now.saturating_sub(last) >= self.window_seconds || now < last)
        {
            windows.scoped.retain(|_, queue| {
                prune_window(queue, now, self.window_seconds);
                !queue.is_empty()
            });
            windows.groups.retain(|_, queue| {
                prune_window(queue, now, self.window_seconds);
                !queue.is_empty()
            });
            windows.last_cleanup = Some(now);
        }

        let group_count = windows
            .groups
            .get_mut(group)
            .map(|queue| {
                prune_window(queue, now, self.window_seconds);
                queue.len()
            })
            .unwrap_or(0);
        if group_count as u32 >= self.group_max_attempts {
            return Err(RateLimitRejection::Group);
        }
        let scoped_key = (group.to_owned(), scope.to_owned());
        let scoped_count = windows
            .scoped
            .get_mut(&scoped_key)
            .map(|queue| {
                prune_window(queue, now, self.window_seconds);
                queue.len()
            })
            .unwrap_or(0);
        if scoped_count as u32 >= self.scoped_max_attempts {
            return Err(RateLimitRejection::Scoped);
        }
        windows.scoped.entry(scoped_key).or_default().push_back(now);
        windows
            .groups
            .entry(group.to_owned())
            .or_default()
            .push_back(now);
        Ok(())
    }
}

fn prune_window(queue: &mut VecDeque<i64>, now: i64, window_seconds: i64) {
    queue.retain(|timestamp| *timestamp <= now && now.saturating_sub(*timestamp) < window_seconds);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limiter_is_scoped_and_keeps_a_global_fallback() {
        let config = RateLimitConfig {
            max_attempts: 2,
            window_seconds: 60,
        };
        let scoped = RateLimiter::new(config.clone());
        assert!(scoped.check_and_record("scope-a", 100));
        assert!(scoped.check_and_record("scope-a", 100));
        assert!(!scoped.check_and_record("scope-a", 100));
        assert!(scoped.check_and_record("scope-b", 100));

        let global = RateLimiter::new(config);
        for index in 0..(2 * GLOBAL_RATE_LIMIT_MULTIPLIER) {
            assert!(global.check_and_record(&format!("rotated-{index}"), 100));
        }
        assert!(!global.check_and_record("rotated-overflow", 100));
        assert!(global.check_and_record("rotated-overflow", 160));
    }
}
