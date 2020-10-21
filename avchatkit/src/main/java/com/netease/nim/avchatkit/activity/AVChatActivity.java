package com.netease.nim.avchatkit.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import com.faceunity.FaceU;
import com.faceunity.utils.VersionUtil;
import com.netease.nim.avchatkit.AVChatKit;
import com.netease.nim.avchatkit.AVChatProfile;
import com.netease.nim.avchatkit.R;
import com.netease.nim.avchatkit.common.activity.UI;
import com.netease.nim.avchatkit.common.log.LogUtil;
import com.netease.nim.avchatkit.constant.AVChatExitCode;
import com.netease.nim.avchatkit.controll.AVChatController;
import com.netease.nim.avchatkit.controll.AVChatSoundPlayer;
import com.netease.nim.avchatkit.module.AVChatTimeoutObserver;
import com.netease.nim.avchatkit.module.AVSwitchListener;
import com.netease.nim.avchatkit.module.SimpleAVChatStateObserver;
import com.netease.nim.avchatkit.notification.AVChatNotification;
import com.netease.nim.avchatkit.receiver.PhoneCallStateObserver;
import com.netease.nim.avchatkit.ui.AVChatAudioUI;
import com.netease.nim.avchatkit.ui.AVChatVideoUI;
import com.netease.nimlib.sdk.NIMClient;
import com.netease.nimlib.sdk.Observer;
import com.netease.nimlib.sdk.StatusCode;
import com.netease.nimlib.sdk.auth.AuthServiceObserver;
import com.netease.nimlib.sdk.auth.ClientType;
import com.netease.nimlib.sdk.avchat.AVChatManager;
import com.netease.nimlib.sdk.avchat.constant.AVChatControlCommand;
import com.netease.nimlib.sdk.avchat.constant.AVChatEventType;
import com.netease.nimlib.sdk.avchat.constant.AVChatType;
import com.netease.nimlib.sdk.avchat.constant.AVChatVideoQuality;
import com.netease.nimlib.sdk.avchat.model.AVChatAudioFrame;
import com.netease.nimlib.sdk.avchat.model.AVChatCalleeAckEvent;
import com.netease.nimlib.sdk.avchat.model.AVChatCommonEvent;
import com.netease.nimlib.sdk.avchat.model.AVChatControlEvent;
import com.netease.nimlib.sdk.avchat.model.AVChatData;
import com.netease.nimlib.sdk.avchat.model.AVChatOnlineAckEvent;
import com.netease.nimlib.sdk.avchat.model.AVChatParameters;
import com.netease.nimlib.sdk.avchat.model.AVChatVideoFrame;
import com.netease.nimlib.sdk.avchat.video.AVChatExternalVideoRender;
import com.netease.nrtc.sdk.common.ImageFormat;
import com.netease.nrtc.sdk.common.VideoFilterParameter;
import com.netease.nrtc.sdk.video.VideoFrame;
import com.netease.nrtc.utility.video.VideoUtils;
import com.netease.nrtc.video.coding.VideoFrameFormat;
import com.netease.vcloud.video.effect.VideoEffect;
import com.netease.vcloud.video.effect.VideoEffectFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.logging.LogRecord;

/**
 * 音视频主界面
 * 1、初始化
 * 2、来电去电入口
 * 3、监听
 * 4、切换音视频
 * 5、通知栏提醒
 * 6、faceU
 * Created by winnie on 2017/12/10.
 */

public class AVChatActivity extends UI implements AVChatVideoUI.TouchZoneCallback, AVSwitchListener {
    // constant
    private static final String TAG = "AVChatActivity";
    private static final String KEY_IN_CALLING = "KEY_IN_CALLING";
    private static final String KEY_ACCOUNT = "KEY_ACCOUNT";
    private static final String KEY_DISPLAY_NAME = "KEY_DISPLAY_NAME";
    private static final String KEY_CALL_TYPE = "KEY_CALL_TYPE";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_CALL_CONFIG = "KEY_CALL_CONFIG";
    public static final String INTENT_ACTION_AVCHAT = "INTENT_ACTION_AVCHAT";

    public static final int FROM_BROADCASTRECEIVER = 0; // 来自广播
    public static final int FROM_INTERNAL = 1; // 来自发起方
    public static final int FROM_UNKNOWN = -1; // 未知的入口

    private static final VideoEffect.FilterType[] FILTERS = new VideoEffect.FilterType[]{
            VideoEffect.FilterType.brooklyn,
            VideoEffect.FilterType.calm,
            VideoEffect.FilterType.clean,
            VideoEffect.FilterType.fairytale,
            VideoEffect.FilterType.nature,
            VideoEffect.FilterType.healthy,
            VideoEffect.FilterType.pixar,
            VideoEffect.FilterType.tender,
            VideoEffect.FilterType.whiten

    };

    // view
    private View root;
    private View videoRoot;
    private View audioRoot;
    private View surfaceRoot;

    // state
    private static boolean needFinish = true; // 若来电或去电未接通时，点击home。另外一方挂断通话。从最近任务列表恢复，则finish
    private boolean mIsInComingCall = false;// is incoming call or outgoing call
    private boolean isCallEstablished = false; // 电话是否接通
    private boolean isUserFinish = false;
    private boolean hasOnPause = false; // 是否暂停音视频

    // data
    private AVChatData avChatData; // config for connect video server
    private int state; // calltype 音频或视频
    private String receiverId; // 对方的account
    private String displayName; // 对方的显示昵称

    private AVChatController avChatController;
    private AVChatAudioUI avChatAudioUI; // 音频界面
    private AVChatVideoUI avChatVideoUI; // 视频界面

    // face unity
    private FaceU faceU;

    private VideoEffect mVideoEffect;
    private int mCurWidth, mCurHeight;
    private  boolean isUnInitVideoEffect = false;// 是否销毁滤镜模块
    private int mDropFramesWhenConfigChanged = 0; //丢帧数


    // notification
    private AVChatNotification notifier;

    // 拨打电话
    public static void outgoingCall(Context context, String account, String displayName, int callType, int source) {
        needFinish = false;
        Intent intent = new Intent();
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClass(context, AVChatActivity.class);
        intent.putExtra(KEY_ACCOUNT, account);
        intent.putExtra(KEY_DISPLAY_NAME, displayName);
        intent.putExtra(KEY_IN_CALLING, false);
        intent.putExtra(KEY_CALL_TYPE, callType);
        intent.putExtra(KEY_SOURCE, source);
        context.startActivity(intent);
    }

    // 接听来电
    public static void incomingCall(Context context, AVChatData config, String displayName, int source) {
        needFinish = false;
        Intent intent = new Intent();
        intent.setClass(context, AVChatActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(KEY_CALL_CONFIG, config);
        intent.putExtra(KEY_DISPLAY_NAME, displayName);
        intent.putExtra(KEY_IN_CALLING, true);
        intent.putExtra(KEY_SOURCE, source);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 若来电或去电未接通时，点击home。另外一方挂断通话。从最近任务列表恢复，则finish
        if (needFinish) {
            finish();
            return;
        }

        // 启动成功，取消timeout处理
        AVChatProfile.getInstance().activityLaunched();

        dismissKeyguard();

        root = LayoutInflater.from(this).inflate(R.layout.avchat_activity, null);
        setContentView(root);

        parseIntent();

        initData();

        showUI();

        registerObserves(true);

        notifier = new AVChatNotification(this);
        notifier.init(receiverId != null ? receiverId : avChatData.getAccount(), displayName);

        // face unity
        initFaceU();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cancelCallingNotifier();

        if (hasOnPause) {
            avChatVideoUI.onResume();
            avChatController.resumeVideo();
            hasOnPause = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        avChatController.pauseVideo();
        hasOnPause = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        activeCallingNotifier();
    }


    @Override
    public void onBackPressed() {
        // 禁用返回键
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        //界面销毁时强制尝试挂断，防止出现红米Note 4X等手机在切后台点击杀死程序时，实际没有杀死的情况
        try {
            manualHangUp(AVChatExitCode.HANGUP);
        } catch (Exception e) {

        }

        if (avChatAudioUI != null) {
            avChatAudioUI.onDestroy();
        }

        if (avChatVideoUI != null) {
            avChatVideoUI.onDestroy();
        }

        registerObserves(false);
        AVChatProfile.getInstance().setAVChatting(false);
        cancelCallingNotifier();
        destroyFaceU();
        needFinish = true;
    }

    // 设置窗口flag，亮屏并且解锁/覆盖在锁屏界面上
    private void dismissKeyguard() {
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
    }

    /**
     * ************************ 初始化 ***************************
     */

    private void parseIntent() {
        mIsInComingCall = getIntent().getBooleanExtra(KEY_IN_CALLING, false);
        displayName = getIntent().getStringExtra(KEY_DISPLAY_NAME);
        switch (getIntent().getIntExtra(KEY_SOURCE, FROM_UNKNOWN)) {
            case FROM_BROADCASTRECEIVER: // incoming call
                avChatData = (AVChatData) getIntent().getSerializableExtra(KEY_CALL_CONFIG);
                state = avChatData.getChatType().getValue();
                break;
            case FROM_INTERNAL: // outgoing call
                receiverId = getIntent().getStringExtra(KEY_ACCOUNT);
                state = getIntent().getIntExtra(KEY_CALL_TYPE, -1);
                break;
            default:
                break;
        }
    }

    private void initData() {
        avChatController = new AVChatController(this, avChatData);
        avChatAudioUI = new AVChatAudioUI(this, root, displayName, avChatController, this);
        avChatVideoUI = new AVChatVideoUI(this, root, avChatData, displayName, avChatController, this, this);
    }


    private void registerObserves(boolean register) {
//        VideoFrame.asTexture()
        AVChatManager.getInstance().observeAVChatState(avchatStateObserver, register);
        AVChatManager.getInstance().observeHangUpNotification(callHangupObserver, register);
        AVChatManager.getInstance().observeCalleeAckNotification(callAckObserver, register);
        AVChatManager.getInstance().observeControlNotification(callControlObserver, register);
        AVChatTimeoutObserver.getInstance().observeTimeoutNotification(timeoutObserver, register, mIsInComingCall);
        AVChatManager.getInstance().observeOnlineAckNotification(onlineAckObserver, register);
        PhoneCallStateObserver.getInstance().observeAutoHangUpForLocalPhone(autoHangUpForLocalPhoneObserver, register);
        //放到所有UI的基类里面注册，所有的UI实现onKickOut接口
        NIMClient.getService(AuthServiceObserver.class).observeOnlineStatus(userStatusObserver, register);
    }

    /**
     * ************************ 音视频来电去电入口 ***************************
     */

    private void showUI() {
        audioRoot = root.findViewById(R.id.avchat_audio_layout);
        videoRoot = root.findViewById(R.id.avchat_video_layout);
        surfaceRoot = root.findViewById(R.id.avchat_surface_layout);
        if (state == AVChatType.AUDIO.getValue()) {
            // 音频
            audioRoot.setVisibility(View.VISIBLE);
            videoRoot.setVisibility(View.GONE);
            surfaceRoot.setVisibility(View.GONE);
            if (mIsInComingCall) {
                // 来电
                AVChatSoundPlayer.instance().play(AVChatSoundPlayer.RingerTypeEnum.RING);
                avChatAudioUI.showIncomingCall(avChatData);
            } else {
                // 去电
                AVChatSoundPlayer.instance().play(AVChatSoundPlayer.RingerTypeEnum.CONNECTING);
//                avChatAudioUI.doOutGoingCall(receiverId);
                avChatVideoUI.doOutgoingCall(receiverId,1);
            }
        } else {
            // 视频
            audioRoot.setVisibility(View.GONE);
            videoRoot.setVisibility(View.VISIBLE);
            surfaceRoot.setVisibility(View.VISIBLE);
            if (mIsInComingCall) {
                // 来电
                AVChatSoundPlayer.instance().play(AVChatSoundPlayer.RingerTypeEnum.RING);
                avChatVideoUI.showIncomingCall(avChatData);
            } else {
                // 去电
                AVChatSoundPlayer.instance().play(AVChatSoundPlayer.RingerTypeEnum.CONNECTING);
                avChatVideoUI.doOutgoingCall(receiverId,0);
            }
        }

    }

    /**
     * ****************************** 监听器 **********************************
     */

    // 通话过程状态监听
    private SimpleAVChatStateObserver avchatStateObserver = new SimpleAVChatStateObserver() {
        @Override
        public void onAVRecordingCompletion(String account, String filePath) {
            if (account != null && filePath != null && filePath.length() > 0) {
                String msg = "音视频录制已结束, " + "账号：" + account + " 录制文件已保存至：" + filePath;
                Toast.makeText(AVChatActivity.this, msg, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(AVChatActivity.this, "录制已结束.", Toast.LENGTH_SHORT).show();
            }
            if (state == AVChatType.VIDEO.getValue()) {
                avChatVideoUI.resetRecordTip();
            } else {
                avChatAudioUI.resetRecordTip();
            }
        }

        @Override
        public void onAudioRecordingCompletion(String filePath) {
            if (filePath != null && filePath.length() > 0) {
                String msg = "音频录制已结束, 录制文件已保存至：" + filePath;
                Toast.makeText(AVChatActivity.this, msg, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(AVChatActivity.this, "录制已结束.", Toast.LENGTH_SHORT).show();
            }
            if (state == AVChatType.AUDIO.getValue()) {
                avChatAudioUI.resetRecordTip();
            } else {
                avChatVideoUI.resetRecordTip();
            }
        }

        @Override
        public void onLowStorageSpaceWarning(long availableSize) {
            if (state == AVChatType.VIDEO.getValue()) {
                avChatVideoUI.showRecordWarning();
            } else {
                avChatAudioUI.showRecordWarning();
            }
        }

        @Override
        public void onJoinedChannel(int code, String audioFile, String videoFile, int i) {
            LogUtil.d(TAG, "audioFile -> " + audioFile + " videoFile -> " + videoFile);
            handleWithConnectServerResult(code);
        }

        @Override
        public void onUserJoined(String account) {
            LogUtil.d(TAG, "onUserJoin -> " + account);
            if (state == AVChatType.VIDEO.getValue()) {
                avChatVideoUI.initLargeSurfaceView(account);
            }
        }

        @Override
        public void onUserLeave(String account, int event) {
            LogUtil.d(TAG, "onUserLeave -> " + account);
            manualHangUp(AVChatExitCode.HANGUP);
            finish();
        }

        @Override
        public void onCallEstablished() {
            LogUtil.d(TAG, "onCallEstablished");
            //移除超时监听
            AVChatTimeoutObserver.getInstance().observeTimeoutNotification(timeoutObserver, false, mIsInComingCall);
            if (avChatController.getTimeBase() == 0)
                avChatController.setTimeBase(SystemClock.elapsedRealtime());

            if (state == AVChatType.AUDIO.getValue()) {
                avChatAudioUI.showAudioInitLayout();
            } else {
                // 接通以后，自己是小屏幕显示图像，对方是大屏幕显示图像
                avChatVideoUI.initSmallSurfaceView(AVChatKit.getAccount());
                avChatVideoUI.showVideoInitLayout();
            }
            isCallEstablished = true;
        }

        protected boolean mWaterMaskEnable = true;
        private VideoEffect mVideoEffect;
        private Handler mVideoEffectHandler;
        private Bitmap mWaterMaskBitmap;
        protected boolean mIsmWaterMaskAdded;
        private boolean mHasSetFilterType = false;
        private Random mRandom = new Random();


        //filter param hardCode here
        private VideoEffect initFilter(int format) {
            mVideoEffectHandler = new Handler();
            VideoEffect innerVideoEffect = VideoEffectFactory.getVCloudEffect();
            if (format == VideoFrameFormat.kVideoTexture) {
                innerVideoEffect.init(getApplicationContext(), true, true);
            } else {
                innerVideoEffect.init(getApplicationContext(), true, false);
            }
            innerVideoEffect.setBeautyLevel(1);
            innerVideoEffect.setFilterLevel(0.1f);
            return innerVideoEffect;
        }

        @Override
        public boolean onVideoFrameFilter(VideoFrame frame, VideoFrame[] outputFrames, VideoFilterParameter filterParameter) {

            if (isFinishing()) {
                return false;
            }

            if (mVideoEffect == null) {
                mVideoEffect = initFilter(frame.getBuffer().getFormat());
                notifyCapturerConfigChange();
                return false;
            }

            WindowManager wm = (WindowManager) AVChatActivity.this.getSystemService(Context.WINDOW_SERVICE);
            int rotationW = wm.getDefaultDisplay().getRotation();

            switch (rotationW) {
                case Surface.ROTATION_90:
                    rotationW = 90; break;
                case Surface.ROTATION_180:
                    rotationW = 180; break;
                case Surface.ROTATION_270:
                    rotationW = 270; break;
                default:
                    rotationW = 0;
            }

            VideoFrame.TextureBuffer textureBuffer = (VideoFrame.TextureBuffer) frame.getBuffer();
            int textureId = textureBuffer.getTextureId();
            int width = textureBuffer.getWidth();
            int height = textureBuffer.getHeight();

            if (mWaterMaskEnable) {
                if (mWaterMaskBitmap == null) {
                    try {
                        InputStream is = getResources().getAssets().open("mark/video_mark_static_texture.png");
                        mWaterMaskBitmap = BitmapFactory.decodeStream(is);
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (!mIsmWaterMaskAdded) {
                    mIsmWaterMaskAdded = true;
                    mVideoEffect.addWaterMark(mWaterMaskBitmap, 80, 80);
                }
            } else {
                mVideoEffect.addWaterMark(null, 0, 0);
                mIsmWaterMaskAdded = false;
            }

            if (!mHasSetFilterType) {
                mHasSetFilterType = true;
                int ordinal = mRandom.nextInt(200);
                VideoEffect.FilterType filter = FILTERS[ordinal % FILTERS.length];
                mVideoEffect.setFilterType(filter);
                return false;
            }

            int filtedTextureId = mVideoEffect.filterTexture(textureId, width, height);

            if (mWaterMaskEnable) {
                /////调整角度，保证texture是正的
                int rotation;
                if (filterParameter.displayRotation == 0) {//90  //0 + 90 = 90
                    rotation = 90 + rotationW;
                } else if (filterParameter.displayRotation == 90) {//0 //90+180 =270
                    rotation = 180 + rotationW;
                } else if (filterParameter.displayRotation == 270) {//0 //90+0 =90
                    rotation = 0;
                } else { //180  //90  //270 + 270 =  180
                    rotation = 270;
                }
                VideoEffect.TextureData textureDataR = mVideoEffect.rotateTexture(GLES20.GL_TEXTURE_2D, filtedTextureId, width, height, rotation);

                /////根据SDK内部是否有过镜像处理，调整texture
                VideoEffect.TextureData textureDataM; //镜像过的纹理信息
                VideoEffect.TextureData textureData; //没有镜像过的纹理信息
                if (!filterParameter.isInternalMirrored) {
                    textureDataM = textureDataR;
                    textureData = mVideoEffect.mirrorTexture(GLES20.GL_TEXTURE_2D, textureDataR.textureId, textureDataR.width, textureDataR.height);
                } else {
                    textureDataM = mVideoEffect.mirrorTexture(GLES20.GL_TEXTURE_2D, textureDataR.textureId, textureDataR.width, textureDataR.height);
                    textureData = textureDataR;
                }

                boolean needMirrorData = mWaterMaskEnable && filterParameter.isExternalMirror;
                //////给未镜像数据加水印
                VideoEffect.TextureData textureDataW = mVideoEffect.effectWater(textureData, false, 1);
                VideoFrame.TextureBuffer filterBuffer = VideoFrame.asTexture(new Matrix(), textureDataW.width, textureDataW.height, VideoFrame.TextureBuffer.Type.RGB, textureDataW.textureId, textureBuffer, null);
                VideoFrame filterFrame = new VideoFrame(filterBuffer, 0, frame.getTimestampMs());
                outputFrames[0] = filterFrame;
                if (outputFrames[1] != null && !needMirrorData) {
                    outputFrames[1].release();
                    outputFrames[1] = null;
                }

                //////给镜像数据加水印
                if (needMirrorData) {
                    VideoEffect.TextureData textureDataMW = mVideoEffect.effectWater(textureDataM, false, 2);
                    VideoFrame.TextureBuffer filterBufferMW = VideoFrame.asTexture(new Matrix(), textureDataMW.width, textureDataMW.height, VideoFrame.TextureBuffer.Type.RGB, textureDataMW.textureId, textureBuffer, null);
                    VideoFrame filterFrameM = new VideoFrame(filterBufferMW, 0, frame.getTimestampMs());
                    outputFrames[1] = filterFrameM;
                }

            } else {
                VideoFrame.TextureBuffer filterBuffer = VideoFrame.asTexture(textureBuffer.getTransformMatrix(), width, height, VideoFrame.TextureBuffer.Type.RGB, filtedTextureId, textureBuffer, null);

                VideoFrame.Buffer rotatedBuffer = filterBuffer.rotate(filterParameter.frameRotation);
                VideoFrame filterFrame = new VideoFrame(rotatedBuffer, filterParameter.displayRotation, frame.getTimestampMs());
                outputFrames[0] = filterFrame;
                outputFrames[1] = null;
                filterBuffer.release();
            }
            return true;
        }



        protected synchronized void notifyCapturerConfigChange() {
            mDropFramesWhenConfigChanged = 1;
        }

        @Override
        public boolean onVideoFrameFilter(AVChatVideoFrame frame, boolean maybeDualInput) {

            if (faceU != null) {
                faceU.effect(frame.data, frame.width, frame.height, FaceU.VIDEO_FRAME_FORMAT.I420);
            }

            return true;
        }

        @Override
        public boolean onAudioFrameFilter(AVChatAudioFrame frame) {
            return true;
        }

    };

    // 通话过程中，收到对方挂断电话
    Observer<AVChatCommonEvent> callHangupObserver = new Observer<AVChatCommonEvent>() {
        @Override
        public void onEvent(AVChatCommonEvent avChatHangUpInfo) {
            avChatData = avChatController.getAvChatData();
            if (avChatData != null && avChatData.getChatId() == avChatHangUpInfo.getChatId()) {
                hangUpByOther(AVChatExitCode.HANGUP);
                cancelCallingNotifier();
                // 如果是incoming call主叫方挂断，那么通知栏有通知
                if (mIsInComingCall && !isCallEstablished) {
                    activeMissCallNotifier();
                }
            }

        }
    };

    // 呼叫时，被叫方的响应（接听、拒绝、忙）
    Observer<AVChatCalleeAckEvent> callAckObserver = new Observer<AVChatCalleeAckEvent>() {
        @Override
        public void onEvent(AVChatCalleeAckEvent ackInfo) {
            AVChatData info = avChatController.getAvChatData();
            if (info != null && info.getChatId() == ackInfo.getChatId()) {
                if (ackInfo.getEvent() == AVChatEventType.CALLEE_ACK_BUSY) {
                    hangUpByOther(AVChatExitCode.PEER_BUSY);
                } else if (ackInfo.getEvent() == AVChatEventType.CALLEE_ACK_REJECT) {
                    hangUpByOther(AVChatExitCode.REJECT);
                } else if (ackInfo.getEvent() == AVChatEventType.CALLEE_ACK_AGREE) {
                    AVChatSoundPlayer.instance().stop();
                    avChatController.isCallEstablish.set(true);
                }
            }
        }
    };

    Observer<Integer> timeoutObserver = new Observer<Integer>() {
        @Override
        public void onEvent(Integer integer) {
            manualHangUp(AVChatExitCode.CANCEL);
            // 来电超时，自己未接听
            if (mIsInComingCall) {
                activeMissCallNotifier();
            }
            finish();
        }
    };

    // 监听音视频模式切换通知, 对方音视频开关通知
    Observer<AVChatControlEvent> callControlObserver = new Observer<AVChatControlEvent>() {
        @Override
        public void onEvent(AVChatControlEvent netCallControlNotification) {
            handleCallControl(netCallControlNotification);
        }
    };

    // 处理音视频切换请求和对方音视频开关通知
    private void handleCallControl(AVChatControlEvent notification) {
        if (AVChatManager.getInstance().getCurrentChatId() != notification.getChatId()) {
            return;
        }
        switch (notification.getControlCommand()) {
            case AVChatControlCommand.SWITCH_AUDIO_TO_VIDEO:
                incomingAudioToVideo();
                break;
            case AVChatControlCommand.SWITCH_AUDIO_TO_VIDEO_AGREE:
                // 对方同意切成视频啦
                state = AVChatType.VIDEO.getValue();
                avChatVideoUI.onAudioToVideoAgree(notification.getAccount());
                break;
            case AVChatControlCommand.SWITCH_AUDIO_TO_VIDEO_REJECT:
                rejectAudioToVideo();
                Toast.makeText(AVChatActivity.this, R.string.avchat_switch_video_reject, Toast.LENGTH_SHORT).show();
                break;
            case AVChatControlCommand.SWITCH_VIDEO_TO_AUDIO:
                onVideoToAudio();
                break;
            case AVChatControlCommand.NOTIFY_VIDEO_OFF:
                // 收到对方关闭画面通知
                if (state == AVChatType.VIDEO.getValue()) {
                    avChatVideoUI.peerVideoOff();
                }
                break;
            case AVChatControlCommand.NOTIFY_VIDEO_ON:
                // 收到对方打开画面通知
                if (state == AVChatType.VIDEO.getValue()) {
                    avChatVideoUI.peerVideoOn();
                }
                break;
            default:
                Toast.makeText(this, "对方发来指令值：" + notification.getControlCommand(), Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /**
     * 处理连接服务器的返回值
     *
     * @param auth_result
     */
    protected void handleWithConnectServerResult(int auth_result) {
        LogUtil.i(TAG, "result code->" + auth_result);
        if (auth_result == 200) {
            LogUtil.d(TAG, "onConnectServer success");
        } else if (auth_result == 101) { // 连接超时
            avChatController.showQuitToast(AVChatExitCode.PEER_NO_RESPONSE);
        } else if (auth_result == 401) { // 验证失败
            avChatController.showQuitToast(AVChatExitCode.CONFIG_ERROR);
        } else if (auth_result == 417) { // 无效的channelId
            avChatController.showQuitToast(AVChatExitCode.INVALIDE_CHANNELID);
        } else { // 连接服务器错误，直接退出
            avChatController.showQuitToast(AVChatExitCode.CONFIG_ERROR);
        }
    }

    /**
     * 注册/注销同时在线的其他端对主叫方的响应
     */
    Observer<AVChatOnlineAckEvent> onlineAckObserver = new Observer<AVChatOnlineAckEvent>() {
        @Override
        public void onEvent(AVChatOnlineAckEvent ackInfo) {
            if (state == AVChatType.AUDIO.getValue()) {
                avChatData = avChatController.getAvChatData();
            } else {
                avChatData = avChatVideoUI.getAvChatData();
            }
            if (avChatData != null && avChatData.getChatId() == ackInfo.getChatId()) {
                AVChatSoundPlayer.instance().stop();

                String client = null;
                switch (ackInfo.getClientType()) {
                    case ClientType.Web:
                        client = "Web";
                        break;
                    case ClientType.Windows:
                        client = "Windows";
                        break;
                    case ClientType.Android:
                        client = "Android";
                        break;
                    case ClientType.iOS:
                        client = "iOS";
                        break;
                    case ClientType.MAC:
                        client = "Mac";
                        break;
                    default:
                        break;
                }
                if (client != null) {
                    String option = ackInfo.getEvent() == AVChatEventType.CALLEE_ONLINE_CLIENT_ACK_AGREE ? "接听！" : "拒绝！";
                    Toast.makeText(AVChatActivity.this, "通话已在" + client + "端被" + option, Toast.LENGTH_SHORT).show();
                }
                finish();
            }
        }
    };

    Observer<Integer> autoHangUpForLocalPhoneObserver = new Observer<Integer>() {
        @Override
        public void onEvent(Integer integer) {
            hangUpByOther(AVChatExitCode.PEER_BUSY);
        }
    };

    Observer<StatusCode> userStatusObserver = new Observer<StatusCode>() {

        @Override
        public void onEvent(StatusCode code) {
            if (code.wontAutoLogin()) {
                AVChatSoundPlayer.instance().stop();
                AVChatKit.getAvChatOptions().logout(AVChatActivity.this);
                finish();
            }
        }
    };

    /**
     * ******************** 音视频切换接口 ********************
     */

    @Override
    public void onVideoToAudio() {
        state = AVChatType.AUDIO.getValue();
        videoRoot.setVisibility(View.GONE);
        surfaceRoot.setVisibility(View.GONE);
        audioRoot.setVisibility(View.VISIBLE);
        avChatVideoUI.onVideoToAudio();
        // 判断是否静音，扬声器是否开启，对界面相应控件进行显隐处理。
        avChatAudioUI.onVideoToAudio(AVChatManager.getInstance().isLocalAudioMuted(),
                AVChatManager.getInstance().speakerEnabled(),
                avChatData != null ? avChatData.getAccount() : receiverId);
    }

    @Override
    public void onAudioToVideo() {
        audioRoot.setVisibility(View.GONE);
        videoRoot.setVisibility(View.VISIBLE);
        surfaceRoot.setVisibility(View.VISIBLE);
        avChatVideoUI.onAudioToVideo();
    }

    @Override
    public void onReceiveAudioToVideoAgree() {
        // 同意切换为视频
        state = AVChatType.VIDEO.getValue();
        audioRoot.setVisibility(View.GONE);
        videoRoot.setVisibility(View.VISIBLE);
        surfaceRoot.setVisibility(View.VISIBLE);
        avChatVideoUI.onAudioToVideoAgree(avChatData != null ? avChatData.getAccount() : receiverId);
    }

    private void rejectAudioToVideo() {
        videoRoot.setVisibility(View.GONE);
        surfaceRoot.setVisibility(View.GONE);
        audioRoot.setVisibility(View.VISIBLE);
        avChatAudioUI.showAudioInitLayout();
    }

    private void incomingAudioToVideo() {
        videoRoot.setVisibility(View.GONE);
        surfaceRoot.setVisibility(View.GONE);
        audioRoot.setVisibility(View.VISIBLE);
        avChatAudioUI.showIncomingAudioToVideo();
    }

    /**
     * ****************** 通知栏 ********************
     */
    private void activeCallingNotifier() {
        if (notifier != null && !isUserFinish) {
            notifier.activeCallingNotification(true);
        }
    }

    private void cancelCallingNotifier() {
        if (notifier != null) {
            notifier.activeCallingNotification(false);
        }
    }

    private void activeMissCallNotifier() {
        if (notifier != null) {
            notifier.activeMissCallNotification(true);
        }
    }

    @Override
    public void finish() {
        isUserFinish = true;
        super.finish();
    }

    /**
     * ******************************** face unity 接入 ********************************
     */

    private void initFaceU() {
        showOrHideFaceULayout(false); // hide default

        if (VersionUtil.isCompatible(Build.VERSION_CODES.JELLY_BEAN_MR2) && FaceU.hasAuthorized()) {
            // async load FaceU
            FaceU.createAndAttach(AVChatActivity.this, findView(R.id.avchat_video_face_unity), new FaceU.Response<FaceU>() {
                @Override
                public void onResult(FaceU faceU) {
                    AVChatActivity.this.faceU = faceU;
                    showOrHideFaceULayout(true); // show
                }
            });
        }
    }

    private void destroyFaceU() {
        if (faceU == null) {
            return;
        }

        try {
            faceU.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showOrHideFaceULayout(boolean show) {
        ViewGroup vp = findView(R.id.avchat_video_face_unity);
        for (int i = 0; i < vp.getChildCount(); i++) {
            vp.getChildAt(i).setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onTouch() {
//        if (faceU == null) {
//            return;
//        }

        if(AVChatManager.getInstance().getParameter(AVChatParameters.KEY_VIDEO_QUALITY) == AVChatVideoQuality.QUALITY_480P){
            AVChatManager.getInstance().setParameter(AVChatParameters.KEY_VIDEO_QUALITY, AVChatVideoQuality.QUALITY_720P);
            Toast.makeText(this, "切换至720P", Toast.LENGTH_SHORT).show();
        }else {
            AVChatManager.getInstance().setParameter(AVChatParameters.KEY_VIDEO_QUALITY, AVChatVideoQuality.QUALITY_480P);
            Toast.makeText(this, "切换至480P", Toast.LENGTH_SHORT).show();
        }

//        faceU.showOrHideLayout();
    }

    // 主动挂断
    private void manualHangUp(int exitCode) {
        releaseVideo();
        avChatController.hangUp(exitCode);
    }

    // 被对方挂断
    private void hangUpByOther(int exitCode) {
        if (exitCode == AVChatExitCode.PEER_BUSY) {
            avChatController.hangUp(AVChatExitCode.HANGUP);
            finish();
        } else {
            releaseVideo();
            avChatController.onHangUp(exitCode);
        }
    }

    private void releaseVideo() {
        if (state == AVChatType.VIDEO.getValue()) {
            avChatVideoUI.releaseVideo();
        }
    }

}
