# posthog-android: an opted-out SDK still sends requests

Reported in [PostHog/posthog-android#919](https://github.com/PostHog/posthog-android/issues/919).

While opted out, the SDK still sends requests to PostHog:

1. `identify()` and `reset()` each send a `POST /flags` with the distinct id, the anonymous id and the device id, even when the SDK was set up opted out and never opted in.
2. Events captured before `optOut()` are still uploaded after it. Opting out stops new captures but leaves the queue running, and its timer sends what it holds.

PostHog's docs say opting out ["will prevent **all data** from being captured and sent to PostHog"](https://posthog.com/docs/privacy/data-collection).

## Reproduction

Needs JDK 17 or later.

```sh
./gradlew -q run
```

The program runs `com.posthog:posthog` 6.48.0, the core that posthog-android 3.75.0 is built on, against a local server that prints every request the SDK makes. Output from a fresh clone (timings vary):

```
1. A user who never opted in: identify() and reset() while opted out
   165 ms  isOptOut() = true
   168 ms  identify("user-a")
   168 ms  reset()
   179 ms    -> GET /array/phc_repro/config
   201 ms    -> POST /flags/ distinct_id=[user-a]
   206 ms    -> POST /flags/ distinct_id=[01a19430-48cd-7dae-9d54-128fcce98607]

2. A user opts out after an event was captured
  1676 ms  capture("Lesson Started") as user-b
  1677 ms    -> GET /array/phc_repro/config
  1677 ms  optOut(), isOptOut() = true
  1678 ms    -> POST /flags/ distinct_id=[user-b]
  2686 ms    -> POST /batch events=[$identify, Lesson Started] distinct_id=[user-b]
```

In scenario 2, the `/flags` request comes from the `identify("user-b")` made before opting out, which is expected. The `/batch` a second later, after `optOut()`, isn't.

## Expected

Nothing reaches PostHog while the SDK is opted out: no `/flags` request from `identify()` or `reset()`, and no upload of events captured before `optOut()`.

## Actual

- `identify()` ([PostHog.kt#L1669](https://github.com/PostHog/posthog-android/blob/core-v6.48.0/posthog/src/main/java/com/posthog/PostHog.kt#L1669)) and `reset()` ([PostHog.kt#L2276](https://github.com/PostHog/posthog-android/blob/core-v6.48.0/posthog/src/main/java/com/posthog/PostHog.kt#L2276)) call `reloadFeatureFlags()`, which checks only `isEnabled()` ([PostHog.kt#L1940](https://github.com/PostHog/posthog-android/blob/core-v6.48.0/posthog/src/main/java/com/posthog/PostHog.kt#L1940)), not the opt-out. `preloadFeatureFlags = false` doesn't stop it.
- `optOut()` ([PostHog.kt#L1396](https://github.com/PostHog/posthog-android/blob/core-v6.48.0/posthog/src/main/java/com/posthog/PostHog.kt#L1396)) uninstalls integrations but leaves the queue running. `PostHogQueue.flush()` ([PostHogQueue.kt#L317](https://github.com/PostHog/posthog-android/blob/core-v6.48.0/posthog/src/main/java/com/posthog/internal/PostHogQueue.kt#L317)) checks connectivity and backoff, not the opt-out, so the timer uploads events captured before it. The queue is on disk, so events captured offline stay queued and go out later, including after another user on the same device opts in.
- `setup()` also fetches remote config while opted out ([PostHog.kt#L406](https://github.com/PostHog/posthog-android/blob/core-v6.48.0/posthog/src/main/java/com/posthog/PostHog.kt#L406)). That request carries no user data, but it still reaches PostHog.

## Environment

- `com.posthog:posthog` 6.48.0 (posthog-android 3.75.0)
- Kotlin 2.4.21, Gradle 9.8.2, JDK 25
- macOS 27.2 (arm64)
