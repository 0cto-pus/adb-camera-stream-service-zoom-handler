# Camera Stream and Auto Zoom On Android

## Features

- Detects barcodes from long distances using an AI model (Model and codes in different repository)
- Continuously streams the camera in the background
- Automatically adjusts camera zoom after barcode detection
- Improves barcode readability for distant or small barcodes
- Designed for warehouse, inventory, and industrial scanning scenarios

## How It Works

1. The AI model analyzes camera frames in real time.
2. When a distant barcode is detected, the system estimates its position and size.
3. A background Android service manages the camera stream.
4. Camera zoom is automatically adjusted to focus on the detected barcode.
5. The barcode becomes clearer and easier to scan.

## Technologies

- Android (Kotlin / Java)
- Camera APIs
- AI / Object Detection Model
- Background Services
- Barcode Processing

## Use Cases

- Warehouse management
- Inventory tracking
- Logistics operations
- Industrial barcode scanning
- Tunnel barcode scanning

## Goal

The aim of this project is to improve barcode scanning performance for distant objects by combining AI detection with automatic camera stream and zoom control.
