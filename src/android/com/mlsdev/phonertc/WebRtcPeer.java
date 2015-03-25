package com.mlsdev.phonertc;

import android.app.Activity;
import android.os.Handler;
import android.util.Log;
//import com.mlsdev.phonertc.VideoTrackRendererPair;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebRtcPeer {
    private static final String TAG = WebRtcPeer.class.getSimpleName();

    private Activity mContext;
    private PeerConnectionFactory mPeerConnectionFactory;
    private PeerConnection mPeerConnection;
    private MediaStream mLocalMediaStream;
    private RtcListener mListener;

    static final LooperThread webRtcLT = new LooperThread();

    static {
        webRtcLT.start();
    }

    private AudioSource mAudioSource;
    private VideoSource mVideoSource;
    private VideoCapturer mVideoCapturer;

    private final Boolean[] _quit = new Boolean[] { false };

    public WebRtcPeer(Activity context, RtcListener listener) {
        mContext = context;
        mPeerConnectionFactory = new PeerConnectionFactory();
        mListener = listener;
    }

    public void createOffer() {
        LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<PeerConnection.IceServer>();

        iceServers.add(new PeerConnection.IceServer("stun:23.21.150.121"));
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
        iceServers.add(new PeerConnection.IceServer("stun:stun3.l.google.com:19302"));

        MediaConstraints pcConstraints = new MediaConstraints();

        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("googImprovedWifiBwe", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("googOpusFec", "true"));


        mPeerConnection = mPeerConnectionFactory.createPeerConnection(iceServers, pcConstraints, new PeerConnectionObserver());

        initLocalMedia();

//        setCamera();
//        if (mLocalMediaStream != null) {
//            mPeerConnection.addStream(mLocalMediaStream);
//        }

        mPeerConnection.createOffer(new LocalSdpObserver(), pcConstraints);
    }

    private void initLocalMedia() {
        mLocalMediaStream = mPeerConnectionFactory.createLocalMediaStream("ARDAMS");

        mLocalMediaStream.addTrack(createAudioTrack());

        final VideoTrack videoTrack = createVideoTrack();
        mLocalMediaStream.addTrack(videoTrack);

        mPeerConnection.addStream(mLocalMediaStream);
        mContext.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mListener.onAddLocalVideoTrack(videoTrack);
            }
        });
    }

    private VideoCapturer getVideoCapturer() {
        String[] cameraFacing = { "front", "back" };
        int[] cameraIndex = { 0, 1 };
        int[] cameraOrientation = { 0, 90, 180, 270 };
        for (String facing : cameraFacing) {
            for (int index : cameraIndex) {
                for (int orientation : cameraOrientation) {
                    String name = "Camera " + index + ", Facing " + facing + ", Orientation " + orientation;
                    VideoCapturer capturer = VideoCapturer.create(name);
                    if (capturer != null) {
                        return capturer;
                    }
                }
            }
        }
        throw new RuntimeException("Failed to open capturer");
    }

    private VideoTrack createVideoTrack() {
        mVideoCapturer = getVideoCapturer();
        mVideoSource = mPeerConnectionFactory.createVideoSource(mVideoCapturer, new MediaConstraints());
        return mPeerConnectionFactory.createVideoTrack("ARDAMSv0", mVideoSource);
    }

    private AudioTrack createAudioTrack() {
        mAudioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
        return mPeerConnectionFactory.createAudioTrack("ARDAMSa0", mAudioSource);
    }



    public void processAnswer(final String sdpResponse) {
        webRtcLT.post(new Runnable() {
            public void run() {
                SessionDescription sdp = new SessionDescription(SessionDescription.Type.ANSWER, sdpResponse);
                mPeerConnection.setRemoteDescription(new CallSdpObserver(), sdp);
            }
        });
    }

    public synchronized void finish() {
//        if (mLocalMediaStream != null) {
//            mLocalMediaStream.dispose();
//            mLocalMediaStream = null;
//        }
//        if (mPeerConnection != null) {
//            mPeerConnection.close();
//            mPeerConnection.dispose();
//            mPeerConnection = null;
//        }
        synchronized (_quit[0]) {
            if (_quit[0]) {
                return;
            }

            _quit[0] = true;

            mListener.onDisconnect();

            if (mPeerConnection != null) {
                mPeerConnection.removeStream(mLocalMediaStream);
                mPeerConnection.close();
                mPeerConnection.dispose();
                mPeerConnection = null;
            }

            if (mLocalMediaStream != null) {
                mLocalMediaStream.dispose();
                mLocalMediaStream = null;
            }

            if (mVideoSource != null) {
                mVideoSource.stop();
                mVideoSource.dispose();
                mVideoSource = null;
            }

            if (mVideoCapturer != null) {
                mVideoCapturer.dispose();
                mVideoCapturer = null;
            }

            if (mAudioSource != null) {
                mAudioSource.dispose();
                mAudioSource = null;
            }

        }
    }

    private static String preferPCMU(String sdpDescription) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String pcmu8kRtpMap = null;
        Pattern pcmu8kPattern = Pattern
                .compile("^a=rtpmap:(\\d+) PCMU/8000[\r]?$");
        for (int i = 0; (i < lines.length)
                && (mLineIndex == -1 || pcmu8kRtpMap == null); ++i) {
            if (lines[i].startsWith("m=audio ")) {
                mLineIndex = i;
                continue;
            }
            Matcher pcmu8kMatcher = pcmu8kPattern.matcher(lines[i]);
            if (pcmu8kMatcher.matches()) {
                pcmu8kRtpMap = pcmu8kMatcher.group(1);
                continue;
            }
        }
        if (mLineIndex == -1) {
            Log.d(TAG, "No m=audio line, so can't prefer PCMU");
            return sdpDescription;
        }
        if (pcmu8kRtpMap == null) {
            Log.d(TAG, "No PCMU/8000 line, so can't prefer PCMU");
            return sdpDescription;
        }
        String[] origMLineParts = lines[mLineIndex].split(" ");
        StringBuilder newMLine = new StringBuilder();
        int origPartIndex = 0;
        // Format is: m=<media> <port> <proto> <fmt> ...
        newMLine.append(origMLineParts[origPartIndex++]).append(" ");
        newMLine.append(origMLineParts[origPartIndex++]).append(" ");
        newMLine.append(origMLineParts[origPartIndex++]).append(" ");
        newMLine.append(pcmu8kRtpMap);
        for (; origPartIndex < origMLineParts.length; ++origPartIndex) {
            if (!origMLineParts[origPartIndex].equals(pcmu8kRtpMap)) {
                newMLine.append(" ").append(origMLineParts[origPartIndex]);
            }
        }
        lines[mLineIndex] = newMLine.toString();
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines) {
            newSdpDescription.append(line).append("\r\n");
        }

        return newSdpDescription.toString();
    }

    private static String limitBandwidth(String sdpDescription) {
        String videoMark = "a=rtpmap:100 VP8/90000";
        String sdpMod = sdpDescription.replace(videoMark, videoMark + "\r\n" + "b=AS:200");

        return sdpMod;
    }

    public interface RtcListener {
        public void onInitialized(SessionDescription sdp);
        public void onAddLocalVideoTrack(VideoTrack videoTrack);
        public void onAddRemoteVideoTrack(VideoTrack videoTrack);
        public void onDisconnect();

//        public void onAddLocalStream(MediaStream stream);
//        public void onAddRemoteStream(MediaStream stream);
    }

    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            if (PeerConnection.IceGatheringState.COMPLETE.equals(iceGatheringState)) {
                Log.d(TAG, "onIceGatheringChange complete");
                mContext.runOnUiThread(new Runnable() {
                    public void run() {
                        SessionDescription sdp = mPeerConnection.getLocalDescription();
                        mListener.onInitialized(sdp);
                    }
                });
            }
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {

        }

        @Override
        public void onAddStream(final MediaStream mediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.label());

            mContext.runOnUiThread(new Runnable() {
                public void run() {
                    mListener.onAddRemoteVideoTrack(mediaStream.videoTracks.get(0));
                }
            });
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }
    }

    private class CallSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {

        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }
    }

    private class LocalSdpObserver extends CallSdpObserver {
        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
            webRtcLT.post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "onCreateSuccess sdp");
                    final SessionDescription newSdp = new SessionDescription(sdp.type, limitBandwidth(preferPCMU(sdp.description)));
                    mPeerConnection.setLocalDescription(LocalSdpObserver.this, newSdp);
                }
            });
        }
    }

}
