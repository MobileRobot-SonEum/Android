package com.damum.daycaretemi;

import android.app.Application;
import android.content.Context;

import com.damum.daycaretemi.robot.RobotController;
import com.damum.daycaretemi.server.ControlChannel;

/**
 * 로봇은 하나 → RobotController도 앱 전체에 하나. 구현은 빌드(flavor)별 FlavorModule이 정한다.
 * 서버 명령 채널(ControlChannel)도 앱 전체에 하나: 앱이 켜지면 접속하고 끊기면 재접속한다.
 */
public class DaycareApp extends Application {

    private RobotController robot;
    private ControlChannel channel;

    @Override
    public void onCreate() {
        super.onCreate();
        robot = FlavorModule.createRobot(this);
        robot.start();
        channel = new ControlChannel(this, robot);
        channel.start();   // local.properties 설정이 없으면 시작하지 않고 "설정 필요" 상태로 남음
    }

    public static RobotController robot(Context context) {
        return ((DaycareApp) context.getApplicationContext()).robot;
    }

    /** Activity에서는 onStart에서 addListener, onStop에서 removeListener만 한다. start()는 부르지 않는다. */
    public static ControlChannel channel(Context context) {
        return ((DaycareApp) context.getApplicationContext()).channel;
    }
}
