# Live2D Avatar AI

An Android application that integrates a Live2D avatar with an AI conversation engine.

## Module Breakdown

- **/ui**: Contains UI components like `MainActivity` and `AvatarSurfaceView`.
- **/engine**: Handles animation state machine and emotion inference.
- **/network**: Manages OpenAI API streaming and network requests.
- **/audio**: Handles Speech-to-Text (STT) and Text-to-Speech (TTS).
- **/data**: Manages local memory and conversation history.

## Setup
1. Add your OpenAI API Key in `OpenAIClient`.
2. Place Live2D Cubism SDK libraries in `app/libs` and `app/src/main/jniLibs`.
3. Add your Live2D model files in the `assets` folder.

## Tech Stack
- **Language**: Kotlin
- **Networking**: OkHttp, SSE Streaming
- **UI**: Android View System + OpenGL ES for Live2D
- **AI**: OpenAI GPT-4 (Streaming)
