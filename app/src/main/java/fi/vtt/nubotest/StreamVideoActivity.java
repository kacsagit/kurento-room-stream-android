

package fi.vtt.nubotest;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import fi.vtt.nubomedia.kurentoroomclientandroid.RoomError;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomListener;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomNotification;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomResponse;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMMediaConfiguration;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMPeerConnection;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMWebRTCPeer;
import fi.vtt.nubotest.util.Constants;

/**
 * Activity for receiving the video stream of a peer
 * (based on PeerVideoActivity of Pubnub's video chat tutorial example.
 */
public class StreamVideoActivity extends Activity implements NBMWebRTCPeer.Observer, RoomListener {
    private static final String TAG = "PeerVideoActivity";

    private NBMWebRTCPeer nbmWebRTCPeer;
    private SurfaceViewRenderer localView;

    private Map<Integer, String> videoRequestUserMapping;
    private int publishVideoRequestId;
    private TextView mCallStatus;
    private String username;
    private boolean backPressed = false;
    private Thread backPressedThread = null;

    private Handler mHandler = null;
    private CallState callState;

    private enum CallState {
        IDLE, PUBLISHING, PUBLISHED, WAITING_REMOTE_USER, RECEIVING_REMOTE_USER
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_stream);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler = new Handler();
        localView = (SurfaceViewRenderer) findViewById(R.id.gl_surface_local);
        this.mCallStatus = (TextView) findViewById(R.id.call_status);
        callState = CallState.IDLE;
        MainActivity.getKurentoRoomAPIInstance().addObserver(this);
    }

    @Override
    protected void onStart() {
        super.onStart();

        Bundle extras = getIntent().getExtras();
        this.username = extras.getString(Constants.USER_NAME, "");
        Log.i(TAG, "username: " + username);

        EglBase rootEglBase = EglBase.create();
        localView.init(rootEglBase.getEglBaseContext(), null);
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

        NBMMediaConfiguration peerConnectionParameters = new NBMMediaConfiguration(
                NBMMediaConfiguration.NBMRendererType.OPENGLES,
                NBMMediaConfiguration.NBMAudioCodec.OPUS, 0,
                NBMMediaConfiguration.NBMVideoCodec.VP8, 0,
                new NBMMediaConfiguration.NBMVideoFormat(352, 288, PixelFormat.RGB_888, 20),
                NBMMediaConfiguration.NBMCameraPosition.FRONT);

        videoRequestUserMapping = new HashMap<>();

        nbmWebRTCPeer = new NBMWebRTCPeer(peerConnectionParameters, this, localView, this);
        Log.i(TAG, "Initializing nbmWebRTCPeer...");
        nbmWebRTCPeer.initialize();
        callState = CallState.PUBLISHING;
        mCallStatus.setText("Publishing...");
    }

    @Override
    protected void onStop() {
        endCall();
        super.onStop();
    }

    @Override
    protected void onPause() {
        nbmWebRTCPeer.stopLocalMedia();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nbmWebRTCPeer.startLocalMedia();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_video_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onBackPressed() {
        // If back button has not been pressed in a while then trigger thread and toast notification
        if (!this.backPressed) {
            this.backPressed = true;
            Toast.makeText(this, "Press back again to end.", Toast.LENGTH_SHORT).show();
            this.backPressedThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1000);
                        backPressed = false;
                    } catch (InterruptedException e) {
                        Log.d("VCA-oBP", "Successfully interrupted");
                    }
                }
            });
            this.backPressedThread.start();
        }
        // If button pressed the second time then call super back pressed
        // (eventually calls onDestroy)
        else {
            if (this.backPressedThread != null)
                this.backPressedThread.interrupt();
            super.onBackPressed();
        }
    }

    public void hangup(View view) {
        finish();
    }

    public void switchs(View view) {
        nbmWebRTCPeer.switchCameraPosition();
    }


    /**
     * Terminates the current call and ends activity
     */
    private void endCall() {
        callState = CallState.IDLE;
        try {
            if (nbmWebRTCPeer != null) {
                nbmWebRTCPeer.close();
                nbmWebRTCPeer = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onInitialize() {
        nbmWebRTCPeer.generateOffer("local", true);
    }

    @Override
    public void onLocalSdpOfferGenerated(final SessionDescription sessionDescription, final NBMPeerConnection nbmPeerConnection) {
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
            Log.d(TAG, "Sending " + sessionDescription.type);
            publishVideoRequestId = ++Constants.id;
            MainActivity.getKurentoRoomAPIInstance().sendPublishVideo(sessionDescription.description, false, publishVideoRequestId);
        }
    }

    @Override
    public void onLocalSdpAnswerGenerated(SessionDescription sessionDescription, NBMPeerConnection nbmPeerConnection) {
    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate, NBMPeerConnection nbmPeerConnection) {
        int sendIceCandidateRequestId = ++Constants.id;
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
            MainActivity.getKurentoRoomAPIInstance().sendOnIceCandidate(this.username, iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), sendIceCandidateRequestId);
        }
    }

    @Override
    public void onIceStatusChanged(PeerConnection.IceConnectionState iceConnectionState, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onIceStatusChanged");
    }

    @Override
    public void onRemoteStreamAdded(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onRemoteStreamAdded");
        nbmWebRTCPeer.setActiveMasterStream(mediaStream);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCallStatus.setText("");
            }
        });
    }

    @Override
    public void onRemoteStreamRemoved(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onRemoteStreamRemoved");
    }

    @Override
    public void onPeerConnectionError(String s) {
        Log.e(TAG, "onPeerConnectionError:" + s);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel, NBMPeerConnection connection) {
        Log.i(TAG, "[datachannel] Peer opened data channel");
    }

    @Override
    public void onBufferedAmountChange(long l, NBMPeerConnection connection, DataChannel channel) {

    }

    public void sendHelloMessage(DataChannel channel) {
        byte[] rawMessage = "Hello Peer!".getBytes(Charset.forName("UTF-8"));
        ByteBuffer directData = ByteBuffer.allocateDirect(rawMessage.length);
        directData.put(rawMessage);
        directData.flip();
        DataChannel.Buffer data = new DataChannel.Buffer(directData, false);
        channel.send(data);
    }

    @Override
    public void onStateChange(NBMPeerConnection connection, DataChannel channel) {
        Log.i(TAG, "[datachannel] DataChannel onStateChange: " + channel.state());
        if (channel.state() == DataChannel.State.OPEN) {
            sendHelloMessage(channel);
            Log.i(TAG, "[datachannel] Datachannel open, sending first hello");
        }
    }

    @Override
    public void onMessage(DataChannel.Buffer buffer, NBMPeerConnection connection, DataChannel channel) {
        Log.i(TAG, "[datachannel] Message received: " + buffer.toString());
        sendHelloMessage(channel);
    }


    @Override
    public void onRoomResponse(RoomResponse response) {
        Log.d(TAG, "OnRoomResponse:" + response);
        int requestId = response.getId();

        if (requestId == publishVideoRequestId) {

            SessionDescription sd = new SessionDescription(SessionDescription.Type.ANSWER,
                    response.getValue("sdpAnswer").get(0));

            // Check if we are waiting for publication of our own vide
            if (callState == CallState.PUBLISHING) {
                callState = CallState.PUBLISHED;
                nbmWebRTCPeer.processAnswer(sd, "local");


            }
        }

    }

    @Override
    public void onRoomError(RoomError error) {
        Log.e(TAG, "OnRoomError:" + error);
    }

    @Override
    public void onRoomNotification(RoomNotification notification) {
        Log.i(TAG, "OnRoomNotification (state=" + callState.toString() + "):" + notification);
        Map<String, Object> map = notification.getParams();

        if (notification.getMethod().equals(RoomListener.METHOD_ICE_CANDIDATE)) {
            String sdpMid = map.get("sdpMid").toString();
            int sdpMLineIndex = Integer.valueOf(map.get("sdpMLineIndex").toString());
            String sdp = map.get("candidate").toString();
            IceCandidate ic = new IceCandidate(sdpMid, sdpMLineIndex, sdp);

            if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, "local");
            }
        }

    }

    @Override
    public void onRoomConnected() {
    }

    @Override
    public void onRoomDisconnected() {
    }

}