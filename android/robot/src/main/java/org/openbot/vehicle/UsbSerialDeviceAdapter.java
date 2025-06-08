package org.openbot.vehicle;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.felhr.usbserial.UsbSerialDevice;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import org.openbot.env.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An adapter class to make the new 'com.hoho.android.usbserial' library
 * compatible with the old 'com.felhr.usbserial' API.
 * This allows using modern drivers (e.g., for ESP32-C3) without refactoring
 * the entire UsbConnection class.
 */
public class UsbSerialDeviceAdapter extends UsbSerialDevice {

    private static final String TAG = "UsbSerialDeviceAdapter";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final Logger LOGGER = new Logger();
    private final UsbSerialPort usbSerialPort;
    private SerialInputOutputManager serialIoManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private UsbReadCallback readCallback;

    // Parameters to be applied on open()
    private int baudRate = 115200;
    private int dataBits = 8;
    private int stopBits = 1;
    private int parity = 0;

    private UsbSerialDeviceAdapter(UsbDevice device, UsbDeviceConnection connection, UsbSerialPort port) {
        super(device, connection);
        this.usbSerialPort = port;
    }

    /**
     * Factory method to create an instance of the adapter.
     * It uses the new library's prober to find a compatible driver.
     *
     * @param device     The UsbDevice to connect to.
     * @param connection An active UsbDeviceConnection.
     * @return An instance of UsbSerialDeviceAdapter if a driver is found, otherwise null.
     */
    public static UsbSerialDeviceAdapter create(UsbDevice device, UsbDeviceConnection connection) {
        if (connection == null) {
            return null;
        }

        // Use the default prober to find a driver for the device
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            // If default prober fails, try a custom prober for known devices like ESP32-C3
            driver = getCustomProber(device).probeDevice(device);
        }

        if (driver != null && !driver.getPorts().isEmpty()) {
            UsbSerialPort port = driver.getPorts().get(0);
            return new UsbSerialDeviceAdapter(device, connection, port);
        }

        LOGGER.e("No supported driver found for device: " + device.getDeviceName());
        return null;
    }

    private static UsbSerialProber getCustomProber(UsbDevice device) {
        ProbeTable customTable = new ProbeTable();
        // Add VID/PID for ESP32-C3, S2, S3, etc.
        int vid = device.getVendorId(); // 0x1A86 for Twen ESP32C3-Pro
        int pid = device.getProductId(); // 0x7522 for Twen ESP32C3-Pro
        customTable.addProduct(vid, pid, Ch34xSerialDriver.class); // ESP32-C3
        // Add other custom devices here if needed
        // customTable.addProduct(0x1A86, 0x7523, CH34xSerialDriver.class);
        return new UsbSerialProber(customTable);
    }

    @Override
    public boolean open() {
        if (isOpen) {
            return true;
        }
        try {
            usbSerialPort.open(connection);
            // Apply stored parameters. The user deleted setParameters, but this is the correct way.
            // If it still causes issues, this line can be commented out.
            if (baudRate != -1 && dataBits != -1 && stopBits != -1 && parity != -1) {
                usbSerialPort.setParameters(baudRate, dataBits, stopBits, parity);
            }

            // CRITICAL: Set DTR to true for ESP32-C3 and similar devices
            usbSerialPort.setDTR(true);
            usbSerialPort.setRTS(true); // 同時設定 RTS 通常是個好習慣

            isOpen = true;

            // Start the IO manager if a callback has already been set
            if (this.readCallback != null) {
                startIOManager();
            }
            return true;
        } catch (IOException e) {
            LOGGER.e(e, "Error opening serial port");
            isOpen = false;
            return false;
        }
    }

    @Override
    public void write(byte[] buffer) {
        if (!isOpen) {
            return;
        }
        try {
            usbSerialPort.write(buffer, WRITE_WAIT_MILLIS);
        } catch (IOException e) {
            LOGGER.e(e, "Error writing to serial port");
        }
    }

    @Override
    public int read(UsbReadCallback mCallback) {
        if (this.readCallback != null) {
            // A callback is already registered, stop the old IO manager first
            stopIOManager();
        }
        this.readCallback = mCallback;
        if (isOpen) {
            startIOManager();
        }
        return 0; // Per original API, returns 0 for success
    }

    private void startIOManager() {
        if (usbSerialPort != null && serialIoManager == null) {
            serialIoManager = new SerialInputOutputManager(usbSerialPort, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    // Forward data to the old API's callback
                    if (readCallback != null) {
                        readCallback.onReceivedData(data);
                    }
                }

                @Override
                public void onRunError(Exception e) {
                    LOGGER.e(e, "Serial IO Manager error");
                    // Optionally, you can propagate this error
                }
            });
            executor.submit(serialIoManager);
            LOGGER.d("Serial IO Manager started.");
        }
    }

    private void stopIOManager() {
        if (serialIoManager != null) {
            LOGGER.d("Stopping Serial IO Manager...");
            serialIoManager.stop();
            serialIoManager = null;
        }
    }

    @Override
    public void close() {
        if (!isOpen) {
            return;
        }
        stopIOManager();
        try {
            if (usbSerialPort != null) {
                usbSerialPort.close();
            }
        } catch (IOException e) {
            LOGGER.e(e, "Error closing serial port");
        } finally {
            isOpen = false;
        }
    }

    // --- Implement other abstract methods ---

    @Override
    public void setBaudRate(int baudRate) {
        this.baudRate = baudRate;
    }

    @Override
    public void setDataBits(int dataBits) {
        this.dataBits = dataBits;
    }

    @Override
    public void setStopBits(int stopBits) {
        this.stopBits = stopBits;
    }

    @Override
    public void setParity(int parity) {
        this.parity = parity;
    }

    @Override
    public void setFlowControl(int flowControl) {
        // The new library doesn't have a universal flow control method like the old one.
        // It's handled via setDTR/setRTS. We can log this for now.
        LOGGER.d("setFlowControl is not fully supported in this adapter. DTR is set to true on open.");
    }

    @Override
    public void setBreak(boolean state) {
        if (!isOpen) return;
        try {
            usbSerialPort.setBreak(state);
        } catch (IOException e) {
            LOGGER.e(e, "Error setting break");
        }
    }

    // --- Sync methods are not used by UsbConnection, provide simple implementations ---

    @Override
    public boolean syncOpen() {
        this.asyncMode = false;
        return open();
    }

    @Override
    public void syncClose() {
        close();
    }

    @Override
    public void setRTS(boolean b) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDTR(boolean b) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getCTS(UsbCTSCallback usbCTSCallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getDSR(UsbDSRCallback usbDSRCallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getBreak(UsbBreakCallback usbBreakCallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getFrame(UsbFrameCallback usbFrameCallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getOverrun(UsbOverrunCallback usbOverrunCallback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void getParity(UsbParityCallback usbParityCallback) {
        throw new UnsupportedOperationException();
    }
}