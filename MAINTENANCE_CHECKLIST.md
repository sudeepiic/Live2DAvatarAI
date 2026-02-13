# Maintenance Checklist

Use this list before pushing changes or sharing builds.

1. Secrets and configuration
1. Confirm `local.properties` has `OPENAI_API_KEY` and `DEEPGRAM_API_KEY` for local testing when not hardcoding.
1. If hardcoding keys for a personal device build, verify the APK is not shared and remove keys before any distribution.
1. Rotate keys if a leak is suspected.

1. Logging and privacy
1. Verify no logs include user speech, prompts, or model output.
1. Keep logging behind `BuildConfig.DEBUG` only.

1. Streaming and lifecycle
1. Confirm streaming clients use `readTimeout(0)` for SSE/WebSocket.
1. Ensure TTS WebSocket connects only when needed and disconnects on `onStop`.
1. Verify session finish handling is idempotent.

1. Resource limits
1. Queues for streaming audio/text must be bounded.
1. Check playback thread priority stays at `Thread.NORM_PRIORITY`.

1. Android safety
1. `android:allowBackup="false"` unless a new secure backup plan is implemented.
1. Validate permissions are still minimal and justified.

1. Documentation
1. Keep `README.md` setup steps accurate.
1. Update this checklist if new services or security constraints are added.
