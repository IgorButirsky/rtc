package com.mlsdev.phonertc;

import android.graphics.Point;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
//import com.mlsdev.phonertc.VideoConfig;
//import com.mlsdev.phonertc.VideoGLView;
//import com.mlsdev.phonertc.VideoTrackRendererPair;
//import com.mlsdev.phonertc.WebRtcPeer;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.util.ArrayList;
import java.util.List;

public class PhoneRTCPlugin  extends CordovaPlugin {

    private static final String TAG = PhoneRTCPlugin.class.getSimpleName();

    private WebRtcPeer mClient;
    private VideoConfig _videoConfig;
    private boolean _initializedAndroidGlobals;
    private WebView.LayoutParams _videoParams;
    private VideoGLView _videoView;
    private List<VideoTrackRendererPair> _remoteVideos;
    private VideoTrackRendererPair _localVideo;
    private CallbackContext _callbackContext;

    public PhoneRTCPlugin() {
        _remoteVideos = new ArrayList<VideoTrackRendererPair>();
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute action " + action);
        if ("createSessionObject".equals(action)) {
            _callbackContext = callbackContext;
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    if (!_initializedAndroidGlobals) {
                        PeerConnectionFactory.initializeAndroidGlobals(cordova.getActivity().getApplicationContext(), true, true, VideoRendererGui.getEGLContext());
                        _initializedAndroidGlobals = true;
                    }
                    mClient = new WebRtcPeer(cordova.getActivity(), new WebRtcListener());
                }
            });
            return true;
        } else if ("setVideoView".equals(action)) {
            _videoConfig = VideoConfig.fromJSON(args.getJSONObject(0));

            // make sure it's not junk
            if (_videoConfig.getContainer().getWidth() == 0 || _videoConfig.getContainer().getHeight() == 0) {
                return false;
            }

            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    if (!_initializedAndroidGlobals) {
                        PeerConnectionFactory.initializeAndroidGlobals(cordova.getActivity().getApplicationContext(), true, true, VideoRendererGui.getEGLContext());
                        _initializedAndroidGlobals = true;
                    }

                    _videoParams = new WebView.LayoutParams(
                            _videoConfig.getContainer().getWidth() * _videoConfig.getDevicePixelRatio(),
                            _videoConfig.getContainer().getHeight() * _videoConfig.getDevicePixelRatio(),
                            _videoConfig.getContainer().getX() * _videoConfig.getDevicePixelRatio(),
                            _videoConfig.getContainer().getY() * _videoConfig.getDevicePixelRatio());

                }
            });

            return true;
        } else if ("call".equals(action)) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mClient.createOffer();
                }
            });
            return true;
        } else if (action.equals("receiveMessage")) {
            final JSONObject container = args.getJSONObject(0);
            final String message = container.getString("message");

            cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        JSONObject messageJsonObject = new JSONObject(message);
                        String sdpResponse = messageJsonObject.getString("sdp");
                        mClient.processAnswer(sdpResponse);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });

            return true;
        } else if (action.equals("disconnect")) {

            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mClient.finish();
                }
            });

            return true;
        }
        return false;
    }

    private void createVideoView() {
        Point size = new Point();
        size.set(_videoConfig.getContainer().getWidth() * _videoConfig.getDevicePixelRatio(), _videoConfig.getContainer().getHeight() * _videoConfig.getDevicePixelRatio());

        _videoView = new VideoGLView(cordova.getActivity(), size);
        VideoRendererGui.setView(_videoView);

        webView.addView(_videoView, _videoParams);
    }

    private void sendMessage(JSONObject data) {
        Log.d(TAG, "sendMessage : " + data.toString());
        PluginResult result = new PluginResult(PluginResult.Status.OK, data);
        result.setKeepCallback(true);
        _callbackContext.sendPluginResult(result);
    }

    private void refreshVideoView() {
        int n = _remoteVideos.size();

        for (VideoTrackRendererPair pair : _remoteVideos) {
            if (pair.getVideoRenderer() != null) {
                pair.getVideoTrack().removeRenderer(pair.getVideoRenderer());
            }

            pair.setVideoRenderer(null);
        }

        if (_localVideo != null && _localVideo.getVideoRenderer() != null) {
            _localVideo.getVideoTrack().removeRenderer(_localVideo.getVideoRenderer());
            _localVideo.setVideoRenderer(null);
        }

        if (_videoView != null) {
            webView.removeView(_videoView);
            _videoView = null;
        }

        if (n > 0) {
            createVideoView();

            int rows = n < 9 ? 2 : 3;
            int videosInRow = n == 2 ? 2 : (int)Math.ceil((float)n / rows);

            int videoSize = (int)((float)_videoConfig.getContainer().getWidth() / videosInRow);
            int actualRows = (int)Math.ceil((float)n / videosInRow);

            int y = getCenter(actualRows, videoSize, _videoConfig.getContainer().getHeight());

            int videoIndex = 0;
            int videoSizeAsPercentage = getPercentage(videoSize, _videoConfig.getContainer().getWidth());

            for (int row = 0; row < rows && videoIndex < n; row++) {
                int x = getCenter(row < row - 1 || n % rows == 0 ?
                                videosInRow : n - (Math.min(n, videoIndex + videosInRow) - 1),
                        videoSize,
                        _videoConfig.getContainer().getWidth());

                for (int video = 0; video < videosInRow && videoIndex < n; video++) {
                    VideoTrackRendererPair pair = _remoteVideos.get(videoIndex++);

                    pair.setVideoRenderer(new VideoRenderer(
                            VideoRendererGui.create(x, y, videoSizeAsPercentage, videoSizeAsPercentage,
                                    VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, true)));

                    pair.getVideoTrack().addRenderer(pair.getVideoRenderer());

                    x += videoSizeAsPercentage;
                }

                y += getPercentage(videoSize, _videoConfig.getContainer().getHeight());
            }

            if (_videoConfig.getLocal() != null && _localVideo != null) {
                _localVideo.getVideoTrack().addRenderer(new VideoRenderer(
                        VideoRendererGui.create(getPercentage(_videoConfig.getLocal().getX(), _videoConfig.getContainer().getWidth()),
                                getPercentage(_videoConfig.getLocal().getY(), _videoConfig.getContainer().getHeight()),
                                getPercentage(_videoConfig.getLocal().getWidth(), _videoConfig.getContainer().getWidth()),
                                getPercentage(_videoConfig.getLocal().getHeight(), _videoConfig.getContainer().getHeight()),
                                VideoRendererGui.ScalingType.SCALE_ASPECT_FILL,
                                true)));

            }
        }
    }

    int getCenter(int videoCount, int videoSize, int containerSize) {
        return getPercentage((int)Math.round((containerSize - videoSize * videoCount) / 2.0), containerSize);
    }

    int getPercentage(int localValue, int containerValue) {
        return (int)(localValue * 100.0 / containerValue);
    }

    private class WebRtcListener implements WebRtcPeer.RtcListener {

        @Override
        public void onInitialized(SessionDescription sdp) {
            Log.d(TAG, "WebRtcPeer : onInitialized");
            try {
                JSONObject json = new JSONObject();
                json.put("type", "offer");
                json.put("sdp", sdp.description);
                sendMessage(json);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAddLocalVideoTrack(VideoTrack videoTrack) {
            _localVideo = new VideoTrackRendererPair(videoTrack, null);
            refreshVideoView();
        }

        @Override
        public void onAddRemoteVideoTrack(VideoTrack videoTrack) {
            _remoteVideos.add(new VideoTrackRendererPair(videoTrack, null));
            refreshVideoView();
        }

        @Override
        public void onDisconnect() {
            for (VideoTrackRendererPair pair : _remoteVideos) {
                if (pair.getVideoRenderer() != null) {
                    pair.getVideoTrack().removeRenderer(pair.getVideoRenderer());
                    pair.setVideoRenderer(null);
                }

                pair.setVideoTrack(null);

                _remoteVideos.remove(pair);
                refreshVideoView();
            }

            if (_localVideo != null && _localVideo.getVideoTrack() != null && _localVideo.getVideoRenderer() != null) {
                _localVideo.getVideoTrack().removeRenderer(_localVideo.getVideoRenderer());
                _localVideo = null;
            }

            if (_videoView != null) {
                _videoView.setVisibility(View.GONE);
                webView.removeView(_videoView);
            }

            _remoteVideos.clear();

        }

    }

}
