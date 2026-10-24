# Stakeholder Engagement Log

Project: Guided Capture on a Phone (TransitionX 2026). Partner: Rysera Innovations. Team: RoboNet.
Mentor: Savinu Anusara.

## Meetings and decisions

| Date | Engagement | Points raised by Rysera / decisions | Effect on the project |
|---|---|---|---|
| 06 Sep 2026 | Introductory and first progress meeting with Rysera | Team assigned the "Guided Capture on a Phone" problem only; metric-scale indoor mapping belongs to another team. Requirements: run on a normal phone, use free and open-source components, give accurate results when an ordinary person records the video. Build for Android first. Review the prior research on the problem. | Scope fixed to capture guidance and export (no reconstruction). Prior-work review carried out: photogrammetry capture practice, IntelliCap (ISMAR 2025), "Don't Look Now" (CHI 2024), Schindler and Forstner (2012), Apple Object Capture and RoomPlan, three scanning patents. |
| 22 Sep 2026 (week 3) | Progress review with Rysera | Build the app in React Native, target mid-range phones (for example Samsung Galaxy A-series, Oppo mid-range), keep it open source, keep iOS possible later. | Architecture changed from native Kotlin to React Native with a Kotlin ARCore module (ARCore has no JavaScript interface). Earlier Kotlin engine work was carried over into the native module. |
| 27 Sep 2026 | Technical Design Document v1.0 issued to Rysera and the mentor for review | Twelve open questions sent to Rysera, the most important being pass/fail labels for 20 to 30 test captures, quality criteria, preferred recording resolution and export format. | Design baseline for implementation (`docs/design/Guided_Capture_Technical_Design_v1.0.pdf`). |
| 24 Oct 2026 | Release build 16 published with first on-device results | Installable build, test and validation report (`docs/reports/TEST_REPORT.md`) and this log published in the repository. | Feedback on the working build and the ground-truth labels being requested. |

## Open requests to Rysera

| Request | Why it matters | Status |
|---|---|---|
| Pass/fail labels for 20 to 30 test captures from the reconstruction pipeline | Needed to calibrate thresholds (precision and recall) | Sent with TDD v1.0 (27 Sep 2026); not yet received |
| Quality criteria: distance range, overlap, blur tolerance, lighting | Sets rule thresholds from the pipeline's needs | Sent with TDD v1.0; awaiting answer |
| Preferred recording resolution, frame rate and codec; whether ARCore poses help reconstruction | Export package design | Sent with TDD v1.0; awaiting answer |
| Minimum supported phone models | Device qualification and Depth API reliance | Sent with TDD v1.0; awaiting answer |
| Feedback on the working build | Validates the guidance messages with the client | Being requested with build 16 and progress report 01 (24 Oct 2026) |
