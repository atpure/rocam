## Purpose

Provides a clean and intuitive user interface displaying live camera preview, overlay banner preview, recording indicators, and network configuration information.

## ADDED Requirements

### Requirement: Live Camera Preview and Overlay Display
The main activity SHALL display a live camera preview filling the viewport with an overlay banner showing the current subtitle text in real time.

#### Scenario: Live text preview update
- **WHEN** the subtitle text is updated via HTTP API or UI input
- **THEN** the on-screen preview updates immediately to reflect the exact text being recorded

#### Scenario: Visual recording indicator
- **WHEN** recording starts or stops
- **THEN** the UI updates a prominent recording badge (e.g. red REC dot and duration timer) to clearly show status

### Requirement: Settings and Information Dialog
The UI SHALL provide a settings gear icon at the top of the main screen which opens an information dialog displaying the device's local IP address, active HTTP port, full control URL, and command cheat-sheet.

#### Scenario: Open settings dialog
- **WHEN** the user taps the settings gear icon
- **THEN** an information modal appears displaying the device IP address, allocated port number, complete URL (e.g. `http://192.168.0.15:8080`), and sample curl/HTTP commands for `/start`, `/stop`, `/update-text`, and `/status`
