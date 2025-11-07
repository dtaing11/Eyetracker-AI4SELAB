# AI4SE Eye Tracking Plugin

A JetBrains plugin that integrates real-time **eye-tracking analytics** into the IDE to assist research in **AI for Software Engineering (AI4SE Lab)**.  
The plugin launches a Dockerized Python backend for gaze tracking, streams gaze coordinates into IntelliJ-based IDEs, and enables developers to visualize or log developer attention during coding sessions.

---

## Features

- One-click **Start/Stop Tracking** from the IDE Tools menu  
- **Dockerized Python Runtime** — no manual setup needed  
- **Automatic Image Build & Launch** — builds the container if missing  
- **Live Gaze Data Stream** (JSON from Python → Plugin → IntelliJ log/UI)  
- **Configurable Actions** (pause, resume, label, etc.)  
- Lightweight background tasks (no UI blocking)

---

## Requirements

| Component | Version | Purpose |
|------------|----------|----------|
| **Java SDK** | 17+ | Required for JetBrains Plugin SDK |
| **Gradle** | 8.0+ | Build system |
| **IntelliJ IDEA** | 2024.1+ | Development & testing |
| **Docker Desktop / Daemon** | Latest | Runs the Python tracking backend |

> Make sure Docker is installed and the daemon is running before launching tracking.

---

## Contributing

Contributions are welcome! Please follow these steps:

1. **Fork** the repository  
2. **Create a branch** for your feature or bugfix:
   ```bash
   git checkout -b feature/my-feature
3. **Commit** your changes with clear messages
4. Push your branch and open a Pull Request
