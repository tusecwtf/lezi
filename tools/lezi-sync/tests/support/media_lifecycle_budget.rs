//! Fixture transport guards derived from the endpoint-specific Android budgets.
//! See docs/testing/media-metadata-lifecycle-budgets.md for semantic differences.
use std::{
    process::Command,
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RequestBudget {
    connect_seconds: u64,
    low_speed_seconds: u64,
    elapsed_seconds: u64,
}

impl RequestBudget {
    pub fn for_request(method: &str, path: &str) -> Self {
        let segments: Vec<_> = path.split('/').collect();
        let (connect_seconds, low_speed_seconds, elapsed_seconds) = match (method, &segments[..]) {
            ("GET", ["", "v1", "setup-status"]) => (3, 5, 8),
            ("POST", ["", "v1", "family", "create"])
            | ("POST", ["", "v1", "member", "requests"])
            | ("POST", ["", "v1", "member", "requests", "claim"])
            | ("POST", ["", "v1", "member", "requests", _, "approve-new"]) => (3, 8, 12),
            ("POST", ["", "v1", "causal", "commit"])
            | ("POST", ["", "v1", "conflicts", _, "resolve"]) => (3, 20, 60),
            ("GET", ["", "v1", "conflicts", _]) => (3, 10, 30),
            ("PUT", ["", "v1", "causal", "media", _]) => (5, 90, 240),
            ("GET", ["", "v1", "media", _]) => (3, 8, 30),
            _ => panic!("missing explicit fixture request budget: {method} {path}"),
        };
        Self {
            connect_seconds,
            low_speed_seconds,
            elapsed_seconds,
        }
    }

    pub fn apply(self, command: &mut Command, startup_left: Option<Duration>) {
        let elapsed = Duration::from_secs(self.elapsed_seconds);
        let elapsed = startup_left.map_or(elapsed, |remaining| elapsed.min(remaining));
        let connect = Duration::from_secs(self.connect_seconds).min(elapsed);
        // curl's low-speed window is not HttpURLConnection.readTimeout: it
        // measures transfer rate in both directions, rather than each read.
        // The whole-transfer cap comes from elapsed, NEVER from readTimeout.
        command
            .args(["--connect-timeout", &curl_seconds(connect)])
            .args(["--speed-limit", "1"])
            .args(["--speed-time", &self.low_speed_seconds.to_string()])
            .args(["--max-time", &curl_seconds(elapsed)]);
        // Deliberately no --retry: explicit idempotency replays in the test are
        // assertions, not an automatic retry policy.
    }
}

pub fn curl_seconds(duration: Duration) -> String {
    // curl stores these timeouts in whole milliseconds. A positive sub-ms
    // decimal could truncate to 0 (unlimited), so fail closed instead.
    let millis = duration.as_millis();
    assert!(millis > 0, "less than 1ms cannot bound curl's timeout");
    format!("{}.{:03}", millis / 1_000, millis % 1_000)
}

pub fn startup_remaining(deadline: Instant, now: Instant) -> Duration {
    deadline
        .checked_duration_since(now)
        .filter(|remaining| !remaining.is_zero())
        .expect("media fixture startup exceeded its 15s deadline")
}

pub fn startup_step<T>(
    deadline: Instant,
    mut now: impl FnMut() -> Instant,
    operation: impl FnOnce(Duration) -> T,
) -> T {
    let value = operation(startup_remaining(deadline, now()));
    startup_remaining(deadline, now());
    value
}

#[test]
fn endpoint_budgets_preserve_connect_read_window_and_elapsed_distinctions() {
    for (method, path, expected) in [
        ("GET", "/v1/setup-status", (3, 5, 8)),
        ("POST", "/v1/family/create", (3, 8, 12)),
        ("POST", "/v1/member/requests", (3, 8, 12)),
        ("POST", "/v1/member/requests/id/approve-new", (3, 8, 12)),
        ("POST", "/v1/member/requests/claim", (3, 8, 12)),
        ("POST", "/v1/causal/commit", (3, 20, 60)),
        ("GET", "/v1/conflicts/id", (3, 10, 30)),
        ("POST", "/v1/conflicts/id/resolve", (3, 20, 60)),
        ("PUT", "/v1/causal/media/id", (5, 90, 240)),
        ("GET", "/v1/media/id", (3, 8, 30)),
    ] {
        let budget = RequestBudget::for_request(method, path);
        assert_eq!(
            (
                budget.connect_seconds,
                budget.low_speed_seconds,
                budget.elapsed_seconds
            ),
            expected,
            "{method} {path}"
        );
    }
    let mut command = Command::new("curl");
    RequestBudget::for_request("POST", "/v1/conflicts/id/resolve").apply(&mut command, None);
    let args: Vec<_> = command
        .get_args()
        .map(|arg| arg.to_str().unwrap())
        .collect();
    assert_eq!(
        args,
        [
            "--connect-timeout",
            "3.000",
            "--speed-limit",
            "1",
            "--speed-time",
            "20",
            "--max-time",
            "60.000"
        ]
    );
}

#[test]
#[should_panic(expected = "missing explicit fixture request budget")]
fn unknown_endpoint_cannot_inherit_a_broad_timeout() {
    RequestBudget::for_request("POST", "/v1/unclassified");
}

#[test]
fn startup_deadline_accepts_only_strictly_early_completion() {
    let deadline = Instant::now() + Duration::from_secs(15);
    assert_eq!(
        startup_remaining(deadline, deadline - Duration::from_nanos(1)),
        Duration::from_nanos(1)
    );
    assert!(std::panic::catch_unwind(|| startup_remaining(deadline, deadline)).is_err());
    assert!(std::panic::catch_unwind(|| startup_remaining(
        deadline,
        deadline + Duration::from_nanos(1)
    ))
    .is_err());
    assert_eq!(curl_seconds(Duration::from_micros(1_999)), "0.001");
}

#[test]
#[should_panic(expected = "less than 1ms cannot bound curl's timeout")]
fn submillisecond_dispatch_budget_cannot_become_an_unlimited_curl_timeout() {
    curl_seconds(Duration::from_nanos(1));
}

#[test]
fn startup_step_rejects_success_returned_at_or_after_deadline() {
    let start = Instant::now();
    let deadline = start + Duration::from_secs(15);
    for finish in [deadline, deadline + Duration::from_nanos(1)] {
        let mut clock = [start, finish].into_iter();
        let completed = std::cell::Cell::new(false);
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            startup_step(
                deadline,
                || clock.next().unwrap(),
                |remaining| {
                    assert_eq!(remaining, Duration::from_secs(15));
                    completed.set(true);
                    "ready/setup succeeded"
                },
            )
        }));
        assert!(completed.get(), "the operation did return success");
        assert!(result.is_err(), "late success must not escape the deadline");
    }
    let mut clock = [start, deadline - Duration::from_nanos(1)].into_iter();
    assert_eq!(
        startup_step(deadline, || clock.next().unwrap(), |_| "ready"),
        "ready"
    );
}

#[test]
fn startup_probe_budget_clamps_to_remaining_whole_milliseconds() {
    for (remaining, connect, elapsed) in [
        (Duration::from_secs(15), "3.000", "8.000"),
        (Duration::from_secs(2), "2.000", "2.000"),
        (Duration::from_micros(1_999), "0.001", "0.001"),
    ] {
        let mut command = Command::new("curl");
        RequestBudget::for_request("GET", "/v1/setup-status").apply(&mut command, Some(remaining));
        let args: Vec<_> = command
            .get_args()
            .map(|arg| arg.to_str().unwrap())
            .collect();
        assert_eq!(args[0..2], ["--connect-timeout", connect]);
        assert_eq!(args[6..8], ["--max-time", elapsed]);
    }
    assert!(std::panic::catch_unwind(|| {
        RequestBudget::for_request("GET", "/v1/setup-status")
            .apply(&mut Command::new("curl"), Some(Duration::from_nanos(1)));
    })
    .is_err());
}

#[test]
fn startup_expired_before_dispatch_does_not_invoke_the_operation() {
    let deadline = Instant::now();
    let invoked = std::cell::Cell::new(false);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        startup_step(deadline, || deadline, |_| invoked.set(true));
    }));
    assert!(result.is_err());
    assert!(!invoked.get());
}

#[test]
fn early_ready_does_not_allow_late_setup_success() {
    let start = Instant::now();
    let deadline = start + Duration::from_secs(15);
    let mut instants = [start, start, start, deadline].into_iter();
    let mut now = || instants.next().unwrap();
    assert_eq!(startup_step(deadline, &mut now, |_| "ready"), "ready");
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        startup_step(deadline, &mut now, |_| "setup success")
    }));
    assert!(result.is_err());
}
