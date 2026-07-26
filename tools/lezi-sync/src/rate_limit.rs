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
    global_max_attempts: u32,
    window_seconds: i64,
    windows: Mutex<RateLimitWindows>,
}

#[derive(Default)]
struct RateLimitWindows {
    global: VecDeque<i64>,
    scoped: HashMap<String, VecDeque<i64>>,
    last_cleanup: Option<i64>,
}

impl RateLimiter {
    pub(super) fn new(config: RateLimitConfig) -> Self {
        Self {
            scoped_max_attempts: config.max_attempts,
            global_max_attempts: config
                .max_attempts
                .saturating_mul(GLOBAL_RATE_LIMIT_MULTIPLIER),
            window_seconds: config.window_seconds,
            windows: Mutex::new(RateLimitWindows::default()),
        }
    }

    pub(super) fn check_and_record(&self, scope: &str, now: i64) -> bool {
        let Ok(mut windows) = self.windows.lock() else {
            return false;
        };

        prune_window(&mut windows.global, now, self.window_seconds);
        if windows
            .last_cleanup
            .is_none_or(|last| now.saturating_sub(last) >= self.window_seconds || now < last)
        {
            windows.scoped.retain(|_, queue| {
                prune_window(queue, now, self.window_seconds);
                !queue.is_empty()
            });
            windows.last_cleanup = Some(now);
        }

        if windows.global.len() as u32 >= self.global_max_attempts {
            return false;
        }
        let queue = windows.scoped.entry(scope.to_owned()).or_default();
        prune_window(queue, now, self.window_seconds);
        if queue.len() as u32 >= self.scoped_max_attempts {
            return false;
        }
        queue.push_back(now);
        windows.global.push_back(now);
        true
    }
}

fn prune_window(queue: &mut VecDeque<i64>, now: i64, window_seconds: i64) {
    while queue.front().is_some_and(|timestamp| {
        now < *timestamp || now.saturating_sub(*timestamp) >= window_seconds
    }) {
        queue.pop_front();
    }
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
