# 📱 Focus — AI-Powered Productivity & Digital Wellness App

**Focus** is an Android productivity application built to help students and professionals eliminate distractions, build consistent study habits, and stay accountable through intelligent focus sessions.

Powered by **Firebase** and **Google Gemini AI**, Focus combines app blocking, session tracking, cloud synchronization, and AI-powered motivation into a single productivity platform.

---

# ✨ Features

## 🎯 Focus Sessions

* Create customizable focus sessions.
* Select study topics and session duration.
* Track completed sessions and productivity history.

## 🚫 Smart App Blocking

* Block distracting applications during focus sessions.
* Prevent access to social media, games, and selected apps.
* Automatically restore access when the session ends.

## 🔕 Notification Control

* Reduce interruptions by muting distracting notifications.
* Keep only essential notifications available while studying.

## 🔒 Lock Mode

* Prevent accidental or intentional session termination.
* Optional passcode protection for stronger commitment.

## 🤖 AI Study Coach

Powered by **Google Gemini AI**

* Personalized motivation
* Study guidance
* Productivity suggestions
* Topic-based encouragement
* Monthly AI coaching credits

## ☁️ Firebase Cloud Sync

* Secure authentication
* Cloud Firestore synchronization
* Real-time subscription updates
* Student and Parent account support

## 👨‍👩‍👧 Parent Dashboard

* Monitor linked student accounts
* View productivity statistics
* Track focus sessions
* Support healthy digital habits

## 💳 Premium Subscriptions

Powered by **Razorpay**

### Focus Pro

* Unlimited focus sessions
* Smart app blocking
* Advanced productivity analytics
* 35 AI coaching credits per month

### Focus Parent

Includes everything in **Focus Pro**, plus:

* Parent Dashboard
* Linked student premium access
* Family productivity management
* 35 AI coaching credits per month

---

# 🏗️ Tech Stack

| Category       | Technology                |
| -------------- | ------------------------- |
| Language       | Kotlin                    |
| UI             | Jetpack Compose           |
| Backend        | Firebase Functions        |
| Database       | Cloud Firestore           |
| Authentication | Firebase Authentication   |
| Payments       | Razorpay Subscription API |
| AI             | Google Gemini             |
| Cloud          | Firebase                  |
| Architecture   | MVVM                      |

---

# 🚀 Getting Started

## Prerequisites

* Android Studio (Latest Stable)
* Android SDK 34+
* JDK 17+
* Firebase Project
* Google Gemini API Key
* Razorpay Account (optional for premium features)

---

## Clone the Repository

```bash
git clone https://github.com/HoneyGpt/Focus.git
cd Focus
```

---

## Open the Project

Open the project in **Android Studio** and allow Gradle to synchronize.

---

## Firebase Configuration

1. Create a Firebase project.
2. Add your Android application.
3. Download `google-services.json`.
4. Place it inside:

```
app/google-services.json
```

---

## Configure Environment

The project uses Firebase Cloud Functions and Secret Manager.

Configure:

* Firebase Authentication
* Firestore
* Cloud Functions
* Gemini API
* Razorpay (optional)

Sensitive credentials should **never** be committed to GitHub.

---

## Run the App

1. Connect an Android device or emulator.
2. Build the project.
3. Press **Run**.

---

# 📂 Project Structure

```
Focus/
│
├── app/
│   ├── ui/
│   ├── service/
│   ├── viewmodel/
│   ├── data/
│   └── utils/
│
├── functions/
│   ├── index.js
│   ├── package.json
│   └── ...
│
├── firestore.rules
├── firebase.json
└── README.md
```

---

# 🔒 Security

* Firebase Authentication
* Firestore Security Rules
* Firebase Secret Manager
* Backend payment verification
* Razorpay webhook verification
* Server-side signature validation

---

# 📈 Roadmap

* [ ] Cross-device focus synchronization
* [ ] Desktop companion
* [ ] Wear OS support
* [ ] Focus analytics dashboard
* [ ] AI productivity reports
* [ ] Collaborative study groups
* [ ] Calendar integration

---

# 🤝 Contributing

Contributions, feature suggestions, and bug reports are welcome.

Feel free to fork the repository and submit a Pull Request.

---

# 📄 License

This project is licensed under the MIT License.

---

# 👨‍💻 Developer

**Harshita Bhaskaruni**

Building AI-powered applications focused on productivity, education, and digital wellbeing.

GitHub: https://github.com/HoneyGpt
