package com.example.tts_demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.hjq.permissions.OnPermission;
import com.hjq.permissions.XXPermissions;
import com.sinovoice.sdk.HciSdk;
import com.sinovoice.sdk.HciSdkConfig;
import com.sinovoice.sdk.LogLevel;
import com.sinovoice.sdk.SessionState;
import com.sinovoice.sdk.android.AudioPlayer;
import com.sinovoice.sdk.android.HciAudioManager;
import com.sinovoice.sdk.android.IAudioPlayerHandler;
import com.sinovoice.sdk.asr.LocalAsrConfig;
import com.sinovoice.sdk.audio.HciAudioMetrics;
import com.sinovoice.sdk.audio.HciAudioSink;
import com.sinovoice.sdk.audio.IAudioCB;
import com.sinovoice.sdk.tts.CloudTtsConfig;
import com.sinovoice.sdk.tts.ISynthCB;
import com.sinovoice.sdk.tts.ISynthHandler;
import com.sinovoice.sdk.tts.LocalTtsConfig;
import com.sinovoice.sdk.tts.SynthConfig;
import com.sinovoice.sdk.tts.SynthShortText;
import com.sinovoice.sdk.tts.SynthStream;
import com.sinovoice.sdk.tts.Warning;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity implements IAudioPlayerHandler, ISynthHandler {
  // 日志窗体最大记录的行数，避免溢出问题
  private static final int MAX_LOG_LINES = 5 * 1024;

  private Thread mUiThread;
  protected TextView tv_logview;
  private EditText et_text;
  private Spinner sp_mode;

  static private HciAudioManager am; // 单例模式
  private AudioPlayer audioPlayer;
  private HciSdk sdk;
  private SynthShortText shorttext;
  private SynthStream stream;

  private boolean synthetizing = false, playing = false;

  static private HciSdk createSdk(Context context) {
    String path = Environment.getExternalStorageDirectory().getAbsolutePath();
    path = path + File.separator + context.getPackageName();

    File file = new File(path);
    if (!file.exists()) {
      file.mkdirs();
    }

    HciSdk sdk = new HciSdk();
    HciSdkConfig cfg = new HciSdkConfig();
    // 平台为应用分配的 appkey
    cfg.setAppkey("aicp_app");
    // 平台为应用分配的密钥 (敏感信息，请勿公开)
    cfg.setSecret("QWxhZGRpbjpvcGVuIHNlc2FtZQ");
//    cfg.setSysUrl("https://10.0.1.186:22801");
//    cfg.setCapUrl("http://10.0.1.186:22800");
    cfg.setDataPath(path);
    cfg.setVerifySSL(false);

    Log.e("log-path", path);
    sdk.setLogLevel(LogLevel.D); // 日志级别
    Log.w("sdk-config", cfg.toString());

    sdk.init(cfg, context);
    return sdk;
  }

  static private SynthConfig shorttextConfig() {
    SynthConfig config = new SynthConfig();
    config.setProperty("ChunZhenHe_Common"); // 发音人
    config.setFormat("pcm");
    config.setSampleRate(16000);
    Log.w("config", config.toString());
    return config;
  }



  static private SynthConfig streamConfig() {
    SynthConfig config = new SynthConfig();
    config.setProperty("cn_roumeijuan_common"); // 发音人
    config.setFormat("pcm");
    config.setSampleRate(16000);
    Log.w("config", config.toString());
    return config;
  }

  static private LocalTtsConfig localconfig(){
  LocalTtsConfig config = new LocalTtsConfig();
  config.setCNLib("/sdcard/sinovoicedata/ttsdata/YanLiRui_MQuality_20180828_Common_CNPackage.dat");
  config.setDMLib("/sdcard/sinovoicedata/ttsdata/RouMeiJuan_16K_20180402_Common_DMPackage.dat");
  config.setENLib("/sdcard/sinovoicedata/ttsdata/RouMeiJuan_MQuality_20170815_Chinglish_ENPackage.dat");
  return config;
  }





  static private HciAudioManager createAudioManager(Context context, int sampleRate) {
    // android-21 是一个分界点，安卓系统的音频 API 有明显变化
    if (Build.VERSION.SDK_INT >= 21) {
      AudioAttributes attributes = new AudioAttributes.Builder()
                                       .setUsage(AudioAttributes.USAGE_MEDIA)
                                       .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                       .build();
      // HciAudioManager 默认使用 AudioAttributes.USAGE_VOICE_COMMUNICATION 和
      // AudioAttributes.CONTENT_TYPE_SPEECH。
      // 调用 setAudioAttributes 将覆盖默认设置，从而可以使用 speaker 播放音频
      return HciAudioManager.builder(context)
          .setSampleRate(sampleRate)
          .setAudioAttributes(attributes)
          .create();
    } else {
      // HciAudioManager 默认使用 AudioManager.STREAM_VOICE_CALL。
      // 调用 setStreamType 将覆盖默认设置，从而可以使用 speaker 播放音频
      return HciAudioManager.builder(context)
          .setSampleRate(sampleRate)
          .setStreamType(AudioManager.STREAM_MUSIC)
          .create();
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    XXPermissions.with(this)
            .permission(Manifest.permission.ACCESS_NETWORK_STATE)
            .permission(Manifest.permission.RECORD_AUDIO)
            .permission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .request(new OnPermission() {

              @Override
              public void hasPermission(List<String> granted, boolean all) {
                if (all) {
                  printLog("获取录音和日历权限成功");
                } else {
                  printLog("获取部分权限成功，但部分权限未正常授予");
                }
              }

              public void noPermission(List<String> denied, boolean never) {
                if (never) {
                  printLog("被永久拒绝授权，请手动授予录音和日历权限");
                  // 如果是被永久拒绝就跳转到应用权限系统设置页面
                  XXPermissions.startPermissionActivity(MainActivity.this, denied);
                } else {
                  printLog("获取录音和日历权限失败");
                }
              }
            });

//    XXPermissions.with(this).permission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
//    XXPermissions.with(this).permission(Manifest.permission.RECORD_AUDIO);
//    XXPermissions.with(this).permission(Manifest.permission.MODIFY_AUDIO_SETTINGS);
    mUiThread = Thread.currentThread();
    sdk = createSdk(this);
    shorttext = new SynthShortText(sdk, localconfig());
    stream = new SynthStream(sdk, localconfig());
    if (am == null) {
      am = createAudioManager(this, 16000);
    }
    // AudioPlayer 采样率可以与 HciAudioManager 的采样率不同
    audioPlayer = new AudioPlayer(am, "pcm_s16le_16k", 1000);

    tv_logview = (TextView) findViewById(R.id.tv_logview);
    et_text = (EditText) findViewById(R.id.tv_text);
    sp_mode = (Spinner) findViewById(R.id.sp_mode);
    initEvents();
  }

  private void initEvents() {
    Button btn = (Button) findViewById(R.id.btn);

    // 开始按钮
    btn.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            if (!playing && !synthetizing) {
              // 播放器未在播放，语音合成未在进行。按钮文本为 "开始"
              HciAudioSink sink = audioPlayer.audioSink();
              HciAudioMetrics m = sink.defaultMetrics().clone();
              // 启动播放器前，必须先 startWrite，否则 audioPlayer.start 会死锁
              int ret = sink.startWrite(m);
              if (ret != 0) {
                printLog("HciAudioSink.startWrite return " + ret);
                return;
              }
              // 启动播放器，在 onStart 回调中启动语音合成会话
              printLog("启动播放器");
              playing = audioPlayer.start(AudioManager.STREAM_MUSIC, MainActivity.this);
              if (!playing) {
                sink.endWrite(true);
              }
              return;
            }
            if (synthetizing) {
              // 语音合成中，按钮文本为 "取消合成"
              if (shorttext.state() == SessionState.RUNNING) {
                shorttext.cancel();
              }
              if (stream.state() == SessionState.RUNNING) {
                stream.cancel();
              }
              return;
            }
            // 播放器播放中，语音合成已结束。按钮文本为 "停止播放"
            audioPlayer.stop();
          }
        });
      }
    });

    // 清屏按钮
    btn = (Button) findViewById(R.id.bt_clear);
    btn.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(final View v) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            tv_logview.setText("");
          }
        });
      }
    });

    // 关闭 SDK 按钮
    btn = (Button) findViewById(R.id.bt_close);
    btn.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View arg0) {
        sdk.close();
        findViewById(R.id.btn).setEnabled(false);
      }
    });
  }

  // 启动短文本语音合成会话
  private void shorttextStart() {
    synthetizing = true;
    setButtonText("取消合成"); // 再点击按钮时为取消合成操作
    shorttext.synth(et_text.getText().toString(), shorttextConfig(), new ISynthCB() {
      @Override
      public void run(SynthShortText s, int code, String type, ByteBuffer audio) {
        synthetizing = false;
        // 此回调发生在 HciSdk 事件循环线程，不能执行长时间的阻塞操作
        final HciAudioSink sink = audioPlayer.audioSink();
        if (code != 0) {
          printLog("短文本合成失败: code = " + code);
          sink.endWrite(true);
        } else {
          printLog("短文本合成成功");
          setButtonText("停止播放");
          ByteBuffer data = ByteBuffer.allocateDirect(audio.capacity());
          data.put(audio).flip();
          // 必须拷贝 audio，因为 run 返回后 audio 就不可用了
         // Log.e("crash", "before asyncWrite");
          sink.asyncWrite(data, new IAudioCB() {
            @Override
            public void run(int written) {
            //  Log.e("crash", "after asyncWrite");
              // written 小于 0 会让播放器丢弃缓冲数据并立即停止播放，否则播放完后停止
              sink.endWrite(written < 0);
            }
          });
        }
      }
    });
  }

  // 启动流式语音合成会话
  private void streamStart() {
    synthetizing = true;
    setButtonText("取消合成"); // 再点击按钮时为取消合成操作
    stream.start(et_text.getText().toString(), streamConfig(), this, true);
  }

  private final Runnable scrollLog = new Runnable() {
    @Override
    public void run() {
      ((ScrollView) tv_logview.getParent()).fullScroll(ScrollView.FOCUS_DOWN);
    }
  };

  private void _setButtonText(String text) {
    final Button btn = (Button) findViewById(R.id.btn);
    btn.setText(text);
  }

  private void setButtonText(final String text) {
    if (mUiThread == Thread.currentThread()) {
      _setButtonText(text);
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        _setButtonText(text);
      }
    });
  }

  private void _printLog(String detail) {
    // 日志输出同时记录到日志文件中
    if (tv_logview == null) {
      return;
    }

    // 如日志行数大于上限，则清空日志内容
    if (tv_logview.getLineCount() > MAX_LOG_LINES) {
      tv_logview.setText("");
    }

    // 在当前基础上追加日志
    tv_logview.append(detail + "\n");

    // 二次刷新确保父控件向下滚动能到达底部,解决一次出现多行日志时滚动不到底部的问题
    tv_logview.post(scrollLog);
  }

  private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS");

  private void printLog(String detail) {
    final String message = fmt.format(new Date()) + " " + detail;
    if (mUiThread == Thread.currentThread()) {
      _printLog(message);
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        _printLog(message);
      }
    });
  }

  @Override
  public void onStart(AudioPlayer player) {
    printLog("播放器已启动，启动合成会话");
    if (sp_mode.getSelectedItemPosition() == 0) {
      shorttextStart();
    } else {
      streamStart();
    }
  }

  @Override
  public void onStartFail(AudioPlayer player, String message) {
    printLog("播放器启动失败: " + message);
  }

  @Override
  public void onSinkEnded(AudioPlayer player, boolean cancel) {
    printLog("播放器音频槽已停止写入: cancel = " + cancel);
    player.stop();
  }

  @Override
  public void onStop(AudioPlayer player) {
    printLog("播放器启已停止");
    playing = false;
    if (synthetizing) {
      stream.cancel();
    } else {
      setButtonText("开始"); // 再点击按钮时为开始操作
    }
  }

  @Override
  public void onAudio(AudioPlayer player, ByteBuffer audio, long timestamp) {
    // 反馈播放数据，调用频率较高，一般不做处理
  }

  @Override
  public void onBufferEmpty(AudioPlayer player) {
    printLog("播放缓冲区空");
    player.stop();
  }

  @Override
  public void onError(AudioPlayer player, String message) {
    printLog("播放发生错误: " + message);
    player.stop();
  }

  @Override
  public void onStart(SynthStream s, int code, Warning[] warnings) {
    if (code == 0) {
      printLog("流式合成会话启动成功");
      return;
    }
    printLog("流式合成会话启动失败: code = " + code);
    // 会让播放器丢弃缓冲数据并立即停止播放
    audioPlayer.audioSink().endWrite(true);
  }

  @Override
  public void onEnd(SynthStream s, int reason) {
    synthetizing = false;
    setButtonText("停止播放");
    switch (reason) {
      case NORMAL:
        printLog("流式合成正常结束");
        // 会让播放器播放完所有数据后停止
        audioPlayer.audioSink().endWrite(false);
        break;
      case ABNORMAL:
        printLog("流式合成因发生错误结束");
        // 会让播放器丢弃缓冲数据并立即停止播放
        audioPlayer.audioSink().endWrite(true);
        break;
      case CANCEL:
        printLog("流式合成被取消");
        // 会让播放器丢弃缓冲数据并立即停止播放
        audioPlayer.audioSink().endWrite(true);
        break;
    }
  }

  @Override
  public void onAudio(SynthStream s, ByteBuffer audio, final Runnable done) {
    // 异步写入到播放器中，完成写操作前**不能**调用 done.run() 释放 audio
    Log.e("crash", "before asyncWrite");
    audioPlayer.audioSink().asyncWrite(audio, new IAudioCB() {
      @Override
      public void run(int written) {
        Log.e("crash", "after asyncWrite");
        printLog("播放音频完成写入: " + written);
        // 调用 done.run() 释放 audio
        done.run();
      }
    });
    Log.e("crash", "==== asyncWrite");
  }

  @Override
  public void onError(SynthStream s, int code) {
    printLog("流式合成发生错误: code = " + code);
  }
}
