# LibrePods Linux

A native Linux application to control your AirPods, offering a comprehensive suite of features for an integrated experience.

## Features

*   **Battery Monitoring:** Get real-time battery levels for your left AirPod, right AirPod, and the charging case. Includes charging status indicators.
*   **Noise Control Modes:** Seamlessly switch between:
    *   **Off:** No noise control.
    *   **Transparency:** Hear your surroundings.
    *   **Noise Cancellation:** Block out ambient noise.
    *   **Adaptive:** Dynamically adjusts noise cancellation based on your environment (for supported models like AirPods Pro 2).
*   **Conversational Awareness:** Automatically lowers media volume when you start speaking, allowing for easy conversations without removing your AirPods.
*   **Ear Detection:** Configure how your media playback behaves when you remove or insert your AirPods:
    *   Pause when one AirPod is removed.
    *   Pause when both AirPods are removed.
    *   Never pause.
*   **One Bud ANC Mode:** Enable Active Noise Cancellation even when using only one AirPod (for supported models).
*   **Cross-Device Connectivity:** Experience seamless handoff of your AirPods connection between your Linux machine and an Android device (requires a companion Android application).
*   **AirPods Renaming:** Personalize your AirPods by changing their name directly from the application.
*   **Magic Cloud Keys QR Code:** Generate a QR code to easily transfer your AirPods' "Magic Cloud Keys" for advanced pairing or multi-device setup.
*   **System Tray Integration:** Quick access to essential controls and battery status directly from your system tray.
*   **System Notifications:** Receive desktop notifications for AirPods connection and disconnection events.
*   **Auto-Start on Login:** Option to automatically launch the application when you log into your system.
*   **Adaptive Noise Level Control:** For AirPods Pro 2, fine-tune the intensity of the Adaptive Noise Cancellation.
*   **Automatic Audio Output Switching:** When AirPods are put in ear, the application intelligently switches your audio output to your AirPods when they are placed in your ears and attempts to switch off the AirPods audio output when both are removed.
*   **Bluetooth Connection Retry:** Configurable number of retries for Bluetooth connection attempts to ensure a stable connection.

## Prerequisites

Before building and running LibrePods Linux, ensure you have the following installed:

1.  **Qt6 Development Packages:**
    *   **For Arch Linux / EndeavourOS:**
        ```bash
        sudo pacman -S qt6-base qt6-connectivity qt6-multimedia-ffmpeg qt6-multimedia
        ```
    *   **For Debian / Ubuntu:**
        ```bash
        sudo apt-get install qt6-base-dev qt6-declarative-dev qt6-connectivity-dev qt6-multimedia-dev \
             qml6-module-qtquick-controls qml6-module-qtqml-workerscript qml6-module-qtquick-templates \
             qml6-module-qtquick-window qml6-module-qtquick-layouts
        ```
2.  **Bluetooth Utilities:** `bluetoothctl` (usually part of `bluez` package).
3.  **PulseAudio/PipeWire Utilities:** `pactl` (usually part of `pulseaudio` or `pipewire-pulse` packages).
4.  **Media Player Control Utility:** `playerctl` (for media playback status monitoring and control).
5.  **Your Phone's Bluetooth MAC Address:** (Required for Cross-Device Connectivity). This can typically be found in your phone's Bluetooth settings or "About Device" section.

## Setup

1.  **Configure Phone MAC Address (Optional, for Cross-Device Connectivity):**
    If you plan to use the Cross-Device Connectivity feature, you need to provide your phone's Bluetooth MAC address.
    Open `Settings.qml` and enter your phone's Bluetooth MAC address in the designated field.

2.  **Build the Application:**
    ```bash
    mkdir build
    cd build
    cmake ..
    make -j $(nproc)
    ```

3.  **Run the Application:**
    ```bash
    ./applinux
    ```

## Usage

*   **Left-click the tray icon:** Displays a quick overview of your AirPods' battery status.
*   **Right-click the tray icon:** Opens a context menu with options to:
    *   Toggle Conversational Awareness.
    *   Switch between Noise Control modes (Off, Transparency, Noise Cancellation, Adaptive).
    *   Open the main application window.
    *   Access application settings.
    *   Quit the application.
*   **Main Application Window:** Provides a detailed view of battery levels, current noise control mode, and access to all settings.
*   **Settings Window:** Accessible from the tray icon or the main application window. Here you can configure:
    *   Ear detection behavior.
    *   Cross-Device Connectivity.
    *   Auto-start on login.
    *   System notifications.
    *   Bluetooth connection retry attempts.
    *   Rename your AirPods.
    *   Generate a QR code for Magic Cloud Keys.

## Tips and Tricks

*   **Hide on Start:** To start the application minimized to the system tray, run it with the `--hide` argument:
    ```bash
    ./applinux --hide
    ```
    This is useful when configuring auto-start.
*   **Debug Mode:** For verbose logging and troubleshooting, run the application with the `--debug` argument:
    ```bash
    ./applinux --debug
    ```
*   **Cross-Device Connectivity:** Ensure you have the companion Android application installed on your phone and that you have correctly entered your phone's Bluetooth MAC address in the settings.
*   **Magic Cloud Keys:** The QR code generated for Magic Cloud Keys is a convenient way to transfer specific pairing information to other compatible devices, simplifying multi-device setup.
*   **Audio Control:** LibrePods Linux leverages `pactl` for audio device management. Ensure your PulseAudio or PipeWire setup is functioning correctly for seamless audio switching.
*   **Media Playback Control:** The application uses `playerctl` to monitor and control media playback. Make sure `playerctl` is installed and can interact with your media players.