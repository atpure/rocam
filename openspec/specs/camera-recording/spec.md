# camera-recording Specification

## Purpose
Handles hardware-accelerated video recording, real-time OpenGL ES graphic/text watermark frame blending, and MP4 video encoding.

## Requirements

### Requirement: OpenGL ES Frame Blending
The video pipeline SHALL render camera preview frames into an OpenGL ES texture and draw a semi-transparent background box with custom subtitle text directly onto each encoded video frame.

#### Scenario: Frame synthesis during recording
- **WHEN** the camera produces video frames during an active recording session
- **THEN** OpenGL ES shaders blend the camera frame, background banner/box, and current subtitle text bitmap into the encoder input surface

### Requirement: Real-time Subtitle Text Swapping
The system SHALL update the text texture rendered onto video frames immediately upon text change without video stutter or pipeline re-initialization.

#### Scenario: Real-time text update during encoding
- **WHEN** a text change event is received while video recording is in progress
- **THEN** the OpenGL ES text texture is updated before the next video frame is drawn, causing the new text to appear immediately in the recorded video

### Requirement: MP4 Video and Audio Encoding
The system SHALL encode video using H.264 (AVC) and audio using AAC into standard MP4 container files saved to public Movies/Videos or app storage accessible by the user.

#### Scenario: Completed MP4 file storage
- **WHEN** recording is stopped
- **THEN** the MediaMuxer finalizes the MP4 file, releases hardware codecs, and registers the video with the Android MediaStore

#### Scenario: Audio-video synchronization
- **WHEN** video and microphone audio are captured simultaneously
- **THEN** video and audio timestamps are synchronized in the resulting MP4 container
