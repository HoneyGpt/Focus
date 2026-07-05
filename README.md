# Focus — Productivity & Lock App

Focus is an Android application designed to help users block distractions, maintain productivity streaks, and achieve study goals. It integrates Gemini AI for dynamic inspiration and Firebase for remote monitoring and cloud synchronization.

---

## Features

- ⏱️ **Focus Sessions**: Start focus sessions with configurable durations and study topics.
- 🚫 **App Blocker**: Block distracting applications and games during focus sessions to avoid interruption.
- 🔔 **Notification Muter**: Automatically mute distracting notifications during active focus.
- 🔑 **Lock Mode**: Prevent premature termination of focus sessions with passcode protection.
- 🤖 **Gemini AI Integration**: Receive personalized, AI-driven motivational quotes tailored to your study topics.
- 🏆 **Gamification & Rewards**: Earn streaks and track achievements based on your focus history.
- ☁️ **Firebase Synchronization**: Sync your stats and study history to the cloud. Includes roles for both Students and Parents.

---

## Getting Started

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (latest version recommended)
- Android SDK 34 or higher

### Local Setup & Installation

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/HoneyGpt/Focus.git
   cd Focus
   ```

2. **Open in Android Studio**:
   - Launch Android Studio, select **Open**, and navigate to the project directory.

3. **Configure Environment Variables**:
   - Create a `.env` file in the root directory based on `.env.example`.
   - Add your `GEMINI_API_KEY`, Firebase keys, and Google OAuth credentials.

4. **Run the Project**:
   - Connect a physical Android device or start an emulator.
   - Click **Run** (or `Shift + F10`) in Android Studio.
