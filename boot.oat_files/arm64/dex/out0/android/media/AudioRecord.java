package android.media;

import android.app.ActivityThread;
import android.content.Context;
import android.media.IAudioService.Stub;
import android.media.MediaRecorder.AudioSource;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.sec.enterprise.EnterpriseDeviceManager;
import android.util.ArrayMap;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Collection;

public class AudioRecord {
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDCHANNELMASK = -17;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDFORMAT = -18;
    private static final int AUDIORECORD_ERROR_SETUP_INVALIDSOURCE = -19;
    private static final int AUDIORECORD_ERROR_SETUP_NATIVEINITFAILED = -20;
    private static final int AUDIORECORD_ERROR_SETUP_ZEROFRAMECOUNT = -16;
    public static final int ERROR = -1;
    public static final int ERROR_BAD_VALUE = -2;
    public static final int ERROR_INVALID_OPERATION = -3;
    private static final int NATIVE_EVENT_MARKER = 2;
    private static final int NATIVE_EVENT_NEW_POS = 3;
    public static final int READ_BLOCKING = 0;
    public static final int READ_NON_BLOCKING = 1;
    public static final int RECORDSTATE_RECORDING = 3;
    public static final int RECORDSTATE_STOPPED = 1;
    private static final int SAMPLE_RATE_HZ_MAX = 192000;
    private static final int SAMPLE_RATE_HZ_MIN = 4000;
    public static final int STATE_INITIALIZED = 1;
    public static final int STATE_UNINITIALIZED = 0;
    public static final String SUBMIX_FIXED_VOLUME = "fixedVolume";
    public static final int SUCCESS = 0;
    private static final String TAG = "android.media.AudioRecord";
    private AudioAttributes mAudioAttributes;
    private int mAudioFormat;
    private int mChannelCount;
    private int mChannelIndexMask;
    private int mChannelMask;
    private NativeEventHandler mEventHandler;
    private final IBinder mICallBack;
    private Looper mInitializationLooper;
    private boolean mIsSubmixFullVolume;
    private int mNativeBufferSizeInBytes;
    private long mNativeCallbackCookie;
    private long mNativeDeviceCallback;
    private long mNativeRecorderInJavaObj;
    private OnRecordPositionUpdateListener mPositionListener;
    private final Object mPositionListenerLock;
    private AudioDeviceInfo mPreferredDevice;
    private int mRecordSource;
    private int mRecordingState;
    private final Object mRecordingStateLock;
    private ArrayMap<OnRoutingChangedListener, NativeRoutingEventHandlerDelegate> mRoutingChangeListeners;
    private int mSampleRate;
    private int mSessionId;
    private int mState;

    public static class Builder {
        private AudioAttributes mAttributes;
        private int mBufferSizeInBytes;
        private AudioFormat mFormat;
        private int mSessionId = 0;

        public Builder setAudioSource(int source) throws IllegalArgumentException {
            if (source < 0 || source > MediaRecorder.getAudioSourceMax()) {
                throw new IllegalArgumentException("Invalid audio source " + source);
            }
            this.mAttributes = new android.media.AudioAttributes.Builder().setInternalCapturePreset(source).build();
            return this;
        }

        public Builder setAudioAttributes(AudioAttributes attributes) throws IllegalArgumentException {
            if (attributes == null) {
                throw new IllegalArgumentException("Illegal null AudioAttributes argument");
            } else if (attributes.getCapturePreset() == -1) {
                throw new IllegalArgumentException("No valid capture preset in AudioAttributes argument");
            } else {
                this.mAttributes = attributes;
                return this;
            }
        }

        public Builder setAudioFormat(AudioFormat format) throws IllegalArgumentException {
            if (format == null) {
                throw new IllegalArgumentException("Illegal null AudioFormat argument");
            }
            this.mFormat = format;
            return this;
        }

        public Builder setBufferSizeInBytes(int bufferSizeInBytes) throws IllegalArgumentException {
            if (bufferSizeInBytes <= 0) {
                throw new IllegalArgumentException("Invalid buffer size " + bufferSizeInBytes);
            }
            this.mBufferSizeInBytes = bufferSizeInBytes;
            return this;
        }

        public Builder setSessionId(int sessionId) throws IllegalArgumentException {
            if (sessionId < 0) {
                throw new IllegalArgumentException("Invalid session ID " + sessionId);
            }
            this.mSessionId = sessionId;
            return this;
        }

        public AudioRecord build() throws UnsupportedOperationException {
            if (this.mFormat == null) {
                this.mFormat = new android.media.AudioFormat.Builder().setEncoding(2).setChannelMask(16).build();
            } else {
                if (this.mFormat.getEncoding() == 0) {
                    this.mFormat = new android.media.AudioFormat.Builder(this.mFormat).setEncoding(2).build();
                }
                if (this.mFormat.getChannelMask() == 0 && this.mFormat.getChannelIndexMask() == 0) {
                    this.mFormat = new android.media.AudioFormat.Builder(this.mFormat).setChannelMask(16).build();
                }
            }
            if (this.mAttributes == null) {
                this.mAttributes = new android.media.AudioAttributes.Builder().setInternalCapturePreset(0).build();
            }
            try {
                if (this.mBufferSizeInBytes == 0) {
                    int channelCount = this.mFormat.getChannelCount();
                    AudioFormat audioFormat = this.mFormat;
                    this.mBufferSizeInBytes = channelCount * AudioFormat.getBytesPerSample(this.mFormat.getEncoding());
                }
                AudioRecord record = new AudioRecord(this.mAttributes, this.mFormat, this.mBufferSizeInBytes, this.mSessionId);
                if (record.getState() != 0) {
                    return record;
                }
                throw new UnsupportedOperationException("Cannot create AudioRecord");
            } catch (IllegalArgumentException e) {
                throw new UnsupportedOperationException(e.getMessage());
            }
        }
    }

    private class NativeEventHandler extends Handler {
        private final AudioRecord mAudioRecord;

        NativeEventHandler(AudioRecord recorder, Looper looper) {
            super(looper);
            this.mAudioRecord = recorder;
        }

        public void handleMessage(Message msg) {
            synchronized (AudioRecord.this.mPositionListenerLock) {
                OnRecordPositionUpdateListener listener = this.mAudioRecord.mPositionListener;
            }
            switch (msg.what) {
                case 2:
                    if (listener != null) {
                        listener.onMarkerReached(this.mAudioRecord);
                        return;
                    }
                    return;
                case 3:
                    if (listener != null) {
                        listener.onPeriodicNotification(this.mAudioRecord);
                        return;
                    }
                    return;
                default:
                    AudioRecord.loge("Unknown native event type: " + msg.what);
                    return;
            }
        }
    }

    private class NativeRoutingEventHandlerDelegate {
        private final Handler mHandler;

        NativeRoutingEventHandlerDelegate(AudioRecord record, OnRoutingChangedListener listener, Handler handler) {
            Looper looper;
            if (handler != null) {
                looper = handler.getLooper();
            } else {
                looper = AudioRecord.this.mInitializationLooper;
            }
            if (looper != null) {
                final AudioRecord audioRecord = AudioRecord.this;
                final AudioRecord audioRecord2 = record;
                final OnRoutingChangedListener onRoutingChangedListener = listener;
                this.mHandler = new Handler(looper) {
                    public void handleMessage(Message msg) {
                        if (audioRecord2 != null) {
                            switch (msg.what) {
                                case 1000:
                                    if (onRoutingChangedListener != null) {
                                        onRoutingChangedListener.onRoutingChanged(audioRecord2);
                                        return;
                                    }
                                    return;
                                default:
                                    AudioRecord.loge("Unknown native event type: " + msg.what);
                                    return;
                            }
                        }
                    }
                };
                return;
            }
            this.mHandler = null;
        }

        Handler getHandler() {
            return this.mHandler;
        }
    }

    public interface OnRecordPositionUpdateListener {
        void onMarkerReached(AudioRecord audioRecord);

        void onPeriodicNotification(AudioRecord audioRecord);
    }

    public interface OnRoutingChangedListener {
        void onRoutingChanged(AudioRecord audioRecord);
    }

    private final native int native_check_permission(String str);

    private final native void native_disableDeviceCallback();

    private final native void native_enableDeviceCallback();

    private final native void native_finalize();

    private final native int native_getRoutedDeviceId();

    private final native int native_get_buffer_size_in_frames();

    private final native int native_get_marker_pos();

    private static final native int native_get_min_buff_size(int i, int i2, int i3);

    private final native int native_get_pos_update_period();

    private final native int native_read_in_byte_array(byte[] bArr, int i, int i2, boolean z);

    private final native int native_read_in_direct_buffer(Object obj, int i, boolean z);

    private final native int native_read_in_float_array(float[] fArr, int i, int i2, boolean z);

    private final native int native_read_in_short_array(short[] sArr, int i, int i2, boolean z);

    private final native void native_release();

    private final native boolean native_setInputDevice(int i);

    private final native int native_set_marker_pos(int i);

    private final native int native_set_pos_update_period(int i);

    private final native int native_setup(Object obj, Object obj2, int i, int i2, int i3, int i4, int i5, int[] iArr, String str);

    private final native int native_start(int i, int i2);

    private final native void native_stop();

    public AudioRecord(int audioSource, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes) throws IllegalArgumentException {
        this(new android.media.AudioAttributes.Builder().setInternalCapturePreset(audioSource).build(), new android.media.AudioFormat.Builder().setChannelMask(getChannelMaskFromLegacyConfig(channelConfig, true)).setEncoding(audioFormat).setSampleRate(sampleRateInHz).build(), bufferSizeInBytes, 0);
    }

    public AudioRecord(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes, int sessionId) throws IllegalArgumentException {
        this.mState = 0;
        this.mRecordingState = 1;
        this.mRecordingStateLock = new Object();
        this.mPositionListener = null;
        this.mPositionListenerLock = new Object();
        this.mEventHandler = null;
        this.mInitializationLooper = null;
        this.mNativeBufferSizeInBytes = 0;
        this.mSessionId = 0;
        this.mIsSubmixFullVolume = false;
        this.mICallBack = new Binder();
        this.mRoutingChangeListeners = new ArrayMap();
        this.mPreferredDevice = null;
        this.mRecordingState = 1;
        if (attributes == null) {
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        } else if (format == null) {
            throw new IllegalArgumentException("Illegal null AudioFormat");
        } else {
            int rate;
            Looper myLooper = Looper.myLooper();
            this.mInitializationLooper = myLooper;
            if (myLooper == null) {
                this.mInitializationLooper = Looper.getMainLooper();
            }
            if (attributes.getCapturePreset() == 8) {
                android.media.AudioAttributes.Builder filteredAttr = new android.media.AudioAttributes.Builder();
                for (String tag : attributes.getTags()) {
                    if (tag.equalsIgnoreCase(SUBMIX_FIXED_VOLUME)) {
                        this.mIsSubmixFullVolume = true;
                        Log.v(TAG, "Will record from REMOTE_SUBMIX at full fixed volume");
                    } else {
                        filteredAttr.addTag(tag);
                    }
                }
                filteredAttr.setInternalCapturePreset(attributes.getCapturePreset());
                this.mAudioAttributes = filteredAttr.build();
            } else {
                this.mAudioAttributes = attributes;
            }
            if ((format.getPropertySetMask() & 2) != 0) {
                rate = format.getSampleRate();
            } else {
                rate = AudioSystem.getPrimaryOutputSamplingRate();
                if (rate <= 0) {
                    rate = 44100;
                }
            }
            int encoding = 1;
            if ((format.getPropertySetMask() & 1) != 0) {
                encoding = format.getEncoding();
            }
            audioParamCheck(attributes.getCapturePreset(), rate, encoding);
            if ((format.getPropertySetMask() & 8) != 0) {
                this.mChannelIndexMask = format.getChannelIndexMask();
                this.mChannelCount = format.getChannelCount();
            }
            if ((format.getPropertySetMask() & 4) != 0) {
                this.mChannelMask = getChannelMaskFromLegacyConfig(format.getChannelMask(), false);
                this.mChannelCount = format.getChannelCount();
            } else if (this.mChannelIndexMask == 0) {
                this.mChannelMask = getChannelMaskFromLegacyConfig(1, false);
                this.mChannelCount = AudioFormat.channelCountFromInChannelMask(this.mChannelMask);
            }
            audioBuffSizeCheck(bufferSizeInBytes);
            int[] session = new int[]{sessionId};
            if (attributes.getCapturePreset() != AudioSource.HOTWORD || checkMicrophoneEnabled(false)) {
                int initResult = native_setup(new WeakReference(this), this.mAudioAttributes, this.mSampleRate, this.mChannelMask, this.mChannelIndexMask, this.mAudioFormat, this.mNativeBufferSizeInBytes, session, ActivityThread.currentOpPackageName());
                if (initResult != 0) {
                    loge("Error code " + initResult + " when initializing native AudioRecord object.");
                    return;
                }
                this.mSessionId = session[0];
                this.mState = 1;
                return;
            }
            loge("MDM policy does not allow initializing native AudioRecord object.");
        }
    }

    private static int getChannelMaskFromLegacyConfig(int inChannelConfig, boolean allowLegacyConfig) {
        int mask;
        switch (inChannelConfig) {
            case 1:
            case 2:
            case 16:
                mask = 16;
                break;
            case 3:
            case 12:
                mask = 12;
                break;
            case 48:
                mask = inChannelConfig;
                break;
            default:
                throw new IllegalArgumentException("Unsupported channel configuration.");
        }
        if (allowLegacyConfig || (inChannelConfig != 2 && inChannelConfig != 3)) {
            return mask;
        }
        throw new IllegalArgumentException("Unsupported deprecated configuration.");
    }

    private void audioParamCheck(int audioSource, int sampleRateInHz, int audioFormat) throws IllegalArgumentException {
        if (audioSource < 0 || !(audioSource <= MediaRecorder.getAudioSourceMax() || audioSource == AudioSource.RADIO_TUNER || audioSource == AudioSource.HOTWORD || audioSource == 1001)) {
            throw new IllegalArgumentException("Invalid audio source.");
        }
        this.mRecordSource = audioSource;
        if (sampleRateInHz < SAMPLE_RATE_HZ_MIN || sampleRateInHz > SAMPLE_RATE_HZ_MAX) {
            throw new IllegalArgumentException(sampleRateInHz + "Hz is not a supported sample rate.");
        }
        this.mSampleRate = sampleRateInHz;
        switch (audioFormat) {
            case 1:
                this.mAudioFormat = 2;
                return;
            case 2:
            case 3:
            case 4:
            case 100:
            case 101:
            case 102:
            case 103:
            case 104:
            case 105:
                this.mAudioFormat = audioFormat;
                return;
            default:
                throw new IllegalArgumentException("Unsupported sample encoding. Should be ENCODING_PCM_8BIT, ENCODING_PCM_16BIT, or ENCODING_PCM_FLOAT.");
        }
    }

    private void audioBuffSizeCheck(int audioBufferSize) throws IllegalArgumentException {
        int frameSizeInBytes = this.mChannelCount * AudioFormat.getBytesPerSample(this.mAudioFormat);
        if (this.mRecordSource == 7 && this.mAudioFormat != 2) {
            frameSizeInBytes = this.mChannelCount * 1;
        }
        if (audioBufferSize % frameSizeInBytes != 0 || audioBufferSize < 1) {
            throw new IllegalArgumentException("Invalid audio buffer size.");
        }
        this.mNativeBufferSizeInBytes = audioBufferSize;
    }

    public void release() {
        try {
            stop();
        } catch (IllegalStateException e) {
        }
        native_release();
        this.mState = 0;
    }

    protected void finalize() {
        release();
    }

    public int getSampleRate() {
        return this.mSampleRate;
    }

    public int getAudioSource() {
        return this.mRecordSource;
    }

    public int getAudioFormat() {
        return this.mAudioFormat;
    }

    public int getChannelConfiguration() {
        return this.mChannelMask;
    }

    public AudioFormat getFormat() {
        android.media.AudioFormat.Builder builder = new android.media.AudioFormat.Builder().setSampleRate(this.mSampleRate).setEncoding(this.mAudioFormat);
        if (this.mChannelMask != 0) {
            builder.setChannelMask(this.mChannelMask);
        }
        if (this.mChannelIndexMask != 0) {
            builder.setChannelIndexMask(this.mChannelIndexMask);
        }
        return builder.build();
    }

    public int getChannelCount() {
        return this.mChannelCount;
    }

    public int getState() {
        return this.mState;
    }

    public int getRecordingState() {
        int i;
        synchronized (this.mRecordingStateLock) {
            i = this.mRecordingState;
        }
        return i;
    }

    public int getBufferSizeInFrames() {
        return native_get_buffer_size_in_frames();
    }

    public int getNotificationMarkerPosition() {
        return native_get_marker_pos();
    }

    public int getPositionNotificationPeriod() {
        return native_get_pos_update_period();
    }

    public static int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat) {
        int channelCount;
        switch (channelConfig) {
            case 1:
            case 2:
            case 16:
                channelCount = 1;
                break;
            case 3:
            case 12:
            case 48:
                channelCount = 2;
                break;
            case 252:
                channelCount = 6;
                break;
            default:
                loge("getMinBufferSize(): Invalid channel configuration.");
                return -2;
        }
        int size = native_get_min_buff_size(sampleRateInHz, channelCount, audioFormat);
        if (size == 0) {
            return -2;
        }
        if (size == -1) {
            return -1;
        }
        return size;
    }

    public int getAudioSessionId() {
        return this.mSessionId;
    }

    private boolean isAudioRecordAllowed() {
        String packageName = ActivityThread.currentPackageName();
        return packageName == null || native_check_permission(packageName) == 0;
    }

    public void startRecording() throws IllegalStateException {
        if (isAudioRecordAllowed()) {
            checkMicrophoneEnabled();
            if (this.mState != 1) {
                throw new IllegalStateException("startRecording() called on an uninitialized AudioRecord.");
            }
            synchronized (this.mRecordingStateLock) {
                if (native_start(0, 0) == 0) {
                    handleFullVolumeRec(true);
                    this.mRecordingState = 3;
                }
            }
            return;
        }
        Log.e(TAG, "User permission denied!");
    }

    public void startRecording(MediaSyncEvent syncEvent) throws IllegalStateException {
        if (isAudioRecordAllowed()) {
            checkMicrophoneEnabled();
            if (this.mState != 1) {
                throw new IllegalStateException("startRecording() called on an uninitialized AudioRecord.");
            }
            synchronized (this.mRecordingStateLock) {
                if (native_start(syncEvent.getType(), syncEvent.getAudioSessionId()) == 0) {
                    handleFullVolumeRec(true);
                    this.mRecordingState = 3;
                }
            }
            return;
        }
        Log.e(TAG, "User permission denied!");
    }

    public void stop() throws IllegalStateException {
        if (this.mState != 1) {
            throw new IllegalStateException("stop() called on an uninitialized AudioRecord.");
        }
        synchronized (this.mRecordingStateLock) {
            handleFullVolumeRec(false);
            native_stop();
            this.mRecordingState = 1;
        }
    }

    private void handleFullVolumeRec(boolean starting) {
        if (this.mIsSubmixFullVolume) {
            try {
                Stub.asInterface(ServiceManager.getService(Context.AUDIO_SERVICE)).forceRemoteSubmixFullVolume(starting, this.mICallBack);
            } catch (RemoteException e) {
                Log.e(TAG, "Error talking to AudioService when handling full submix volume", e);
            }
        }
    }

    public int read(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return read(audioData, offsetInBytes, sizeInBytes, 0);
    }

    public int read(byte[] audioData, int offsetInBytes, int sizeInBytes, int readMode) {
        boolean z = true;
        if (!checkMicrophoneEnabled()) {
            return -3;
        }
        if ((this.mRecordSource == 1 || this.mRecordSource == 5 || this.mRecordSource == 9 || this.mRecordSource == 10) && !checkAudioRecordEnabled()) {
            return -3;
        }
        if (this.mState != 1 || this.mAudioFormat == 4) {
            return -3;
        }
        if (readMode != 0 && readMode != 1) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return -2;
        } else if (audioData == null || offsetInBytes < 0 || sizeInBytes < 0 || offsetInBytes + sizeInBytes < 0 || offsetInBytes + sizeInBytes > audioData.length) {
            return -2;
        } else {
            if (readMode != 0) {
                z = false;
            }
            return native_read_in_byte_array(audioData, offsetInBytes, sizeInBytes, z);
        }
    }

    public int read(short[] audioData, int offsetInShorts, int sizeInShorts) {
        return read(audioData, offsetInShorts, sizeInShorts, 0);
    }

    public int read(short[] audioData, int offsetInShorts, int sizeInShorts, int readMode) {
        boolean z = true;
        if (!checkMicrophoneEnabled()) {
            return -3;
        }
        if ((this.mRecordSource == 1 || this.mRecordSource == 5 || this.mRecordSource == 9 || this.mRecordSource == 10) && !checkAudioRecordEnabled()) {
            return -3;
        }
        if (this.mState != 1 || this.mAudioFormat == 4) {
            return -3;
        }
        if (readMode != 0 && readMode != 1) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return -2;
        } else if (audioData == null || offsetInShorts < 0 || sizeInShorts < 0 || offsetInShorts + sizeInShorts < 0 || offsetInShorts + sizeInShorts > audioData.length) {
            return -2;
        } else {
            if (readMode != 0) {
                z = false;
            }
            return native_read_in_short_array(audioData, offsetInShorts, sizeInShorts, z);
        }
    }

    public int read(float[] audioData, int offsetInFloats, int sizeInFloats, int readMode) {
        boolean z = true;
        if (!checkMicrophoneEnabled()) {
            return -3;
        }
        if ((this.mRecordSource == 1 || this.mRecordSource == 5 || this.mRecordSource == 9 || this.mRecordSource == 10) && !checkAudioRecordEnabled()) {
            return -3;
        }
        if (this.mState == 0) {
            Log.e(TAG, "AudioRecord.read() called in invalid state STATE_UNINITIALIZED");
            return -3;
        } else if (this.mAudioFormat != 4) {
            Log.e(TAG, "AudioRecord.read(float[] ...) requires format ENCODING_PCM_FLOAT");
            return -3;
        } else if (readMode != 0 && readMode != 1) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return -2;
        } else if (audioData == null || offsetInFloats < 0 || sizeInFloats < 0 || offsetInFloats + sizeInFloats < 0 || offsetInFloats + sizeInFloats > audioData.length) {
            return -2;
        } else {
            if (readMode != 0) {
                z = false;
            }
            return native_read_in_float_array(audioData, offsetInFloats, sizeInFloats, z);
        }
    }

    public int read(ByteBuffer audioBuffer, int sizeInBytes) {
        return read(audioBuffer, sizeInBytes, 0);
    }

    public int read(ByteBuffer audioBuffer, int sizeInBytes, int readMode) {
        boolean z = true;
        if (!checkMicrophoneEnabled()) {
            return -3;
        }
        if ((this.mRecordSource == 1 || this.mRecordSource == 5 || this.mRecordSource == 9 || this.mRecordSource == 10) && !checkAudioRecordEnabled()) {
            return -3;
        }
        if (this.mState != 1) {
            return -3;
        }
        if (readMode != 0 && readMode != 1) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return -2;
        } else if (audioBuffer == null || sizeInBytes < 0) {
            return -2;
        } else {
            if (readMode != 0) {
                z = false;
            }
            return native_read_in_direct_buffer(audioBuffer, sizeInBytes, z);
        }
    }

    public void setRecordPositionUpdateListener(OnRecordPositionUpdateListener listener) {
        setRecordPositionUpdateListener(listener, null);
    }

    public void setRecordPositionUpdateListener(OnRecordPositionUpdateListener listener, Handler handler) {
        synchronized (this.mPositionListenerLock) {
            this.mPositionListener = listener;
            if (listener == null) {
                this.mEventHandler = null;
            } else if (handler != null) {
                this.mEventHandler = new NativeEventHandler(this, handler.getLooper());
            } else {
                this.mEventHandler = new NativeEventHandler(this, this.mInitializationLooper);
            }
        }
    }

    public int setNotificationMarkerPosition(int markerInFrames) {
        if (this.mState == 0) {
            return -3;
        }
        if (markerInFrames < 0) {
            return -2;
        }
        return native_set_marker_pos(markerInFrames);
    }

    public AudioDeviceInfo getRoutedDevice() {
        int deviceId = native_getRoutedDeviceId();
        if (deviceId == 0) {
            return null;
        }
        AudioDeviceInfo[] devices = AudioManager.getDevicesStatic(1);
        for (int i = 0; i < devices.length; i++) {
            if (devices[i].getId() == deviceId) {
                return devices[i];
            }
        }
        return null;
    }

    public void addOnRoutingChangedListener(OnRoutingChangedListener listener, Handler handler) {
        if (listener != null && !this.mRoutingChangeListeners.containsKey(listener)) {
            synchronized (this.mRoutingChangeListeners) {
                if (this.mRoutingChangeListeners.size() == 0) {
                    native_enableDeviceCallback();
                }
                ArrayMap arrayMap = this.mRoutingChangeListeners;
                if (handler == null) {
                    handler = new Handler(this.mInitializationLooper);
                }
                arrayMap.put(listener, new NativeRoutingEventHandlerDelegate(this, listener, handler));
            }
        }
    }

    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener) {
        synchronized (this.mRoutingChangeListeners) {
            if (this.mRoutingChangeListeners.containsKey(listener)) {
                this.mRoutingChangeListeners.remove(listener);
                if (this.mRoutingChangeListeners.size() == 0) {
                    native_disableDeviceCallback();
                }
            }
        }
    }

    private void broadcastRoutingChange() {
        synchronized (this.mRoutingChangeListeners) {
            Collection<NativeRoutingEventHandlerDelegate> values = this.mRoutingChangeListeners.values();
        }
        AudioManager.resetAudioPortGeneration();
        for (NativeRoutingEventHandlerDelegate delegate : values) {
            Handler handler = delegate.getHandler();
            if (handler != null) {
                handler.sendEmptyMessage(1000);
            }
        }
    }

    public int setPositionNotificationPeriod(int periodInFrames) {
        if (this.mState == 0 || periodInFrames < 0) {
            return -3;
        }
        return native_set_pos_update_period(periodInFrames);
    }

    private static boolean checkMicrophoneEnabled() {
        boolean enabled;
        try {
            enabled = EnterpriseDeviceManager.getInstance().getRestrictionPolicy().isMicrophoneEnabled(true);
        } catch (Exception e) {
            enabled = true;
        }
        if (!enabled) {
            Log.i(TAG, "MICROPHONE IS DISABLED");
            if (Process.myUid() >= 10000 && Process.myUid() <= Process.LAST_APPLICATION_UID) {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
        return enabled;
    }

    private static boolean checkMicrophoneEnabled(boolean showToast) {
        try {
            return EnterpriseDeviceManager.getInstance().getRestrictionPolicy().isMicrophoneEnabled(showToast);
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean checkAudioRecordEnabled() {
        boolean enabled;
        try {
            enabled = EnterpriseDeviceManager.getInstance().getRestrictionPolicy().isAudioRecordAllowed(true);
        } catch (Exception e) {
            enabled = true;
        }
        if (!enabled) {
            Log.i(TAG, "AUDIO RECORD IS DISABLED");
            if (Process.myUid() >= 10000 || Process.myUid() <= Process.LAST_APPLICATION_UID) {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
        return enabled;
    }

    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        int preferredDeviceId = 0;
        if (deviceInfo != null && !deviceInfo.isSource()) {
            return false;
        }
        if (deviceInfo != null) {
            preferredDeviceId = deviceInfo.getId();
        }
        boolean status = native_setInputDevice(preferredDeviceId);
        if (status) {
            synchronized (this) {
                this.mPreferredDevice = deviceInfo;
            }
        }
        return status;
    }

    public AudioDeviceInfo getPreferredDevice() {
        AudioDeviceInfo audioDeviceInfo;
        synchronized (this) {
            audioDeviceInfo = this.mPreferredDevice;
        }
        return audioDeviceInfo;
    }

    private static void postEventFromNative(Object audiorecord_ref, int what, int arg1, int arg2, Object obj) {
        AudioRecord recorder = (AudioRecord) ((WeakReference) audiorecord_ref).get();
        if (recorder != null) {
            if (what == 1000) {
                recorder.broadcastRoutingChange();
                return;
            }
            checkMicrophoneEnabled();
            if (recorder.mEventHandler != null) {
                recorder.mEventHandler.sendMessage(recorder.mEventHandler.obtainMessage(what, arg1, arg2, obj));
            }
        }
    }

    private static void logd(String msg) {
        Log.d(TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, msg);
    }
}