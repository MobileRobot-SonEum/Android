package com.damum.daycaretemi;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.safety.SafetyEvent;
import com.damum.daycaretemi.safety.SafetyEventBus;

/** 연결 확인 화면: RobotController 연결 상태 표시, 연결되면 "안녕하세요". */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DaycareTemi";
    private static final String KEY_GREETED = "greeted";

    private RobotController robot;
    private TextView statusText;
    private boolean greeted;

    private final RobotController.Listener robotListener = new RobotController.Listener() {
        @Override
        public void onReadyChanged(boolean ready) {
            Log.i(TAG, "onReadyChanged: " + ready);
            statusText.setText(ready ? R.string.status_connected : R.string.status_waiting);
            if (ready && !greeted) {
                greeted = true;
                robot.speak("안녕하세요");
            }
        }

        @Override
        public void onPersonDetected(boolean detected) {
            Log.i(TAG, "onPersonDetected: " + detected);
        }
    };

    private final SafetyEventBus.Listener safetyListener = (SafetyEvent e) ->
            Log.i(TAG, "SafetyEvent 수신: " + e);   // 응급 시나리오 흐름은 이후 여기에 연결

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.status_text);
        robot = DaycareApp.robot(this);
        if (savedInstanceState != null) greeted = savedInstanceState.getBoolean(KEY_GREETED);
        FlavorModule.attachDevTools(this, findViewById(R.id.dev_container));
    }

    @Override
    protected void onStart() {
        super.onStart();
        robot.addListener(robotListener);
        SafetyEventBus.get().subscribe(safetyListener);
    }

    @Override
    protected void onStop() {
        robot.removeListener(robotListener);
        SafetyEventBus.get().unsubscribe(safetyListener);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_GREETED, greeted);
    }
}
