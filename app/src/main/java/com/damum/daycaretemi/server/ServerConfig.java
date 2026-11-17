package com.damum.daycaretemi.server;

import com.damum.daycaretemi.BuildConfig;

import java.util.regex.Pattern;

/**
 * 에이전트 서버 접속 설정. 값은 local.properties(커밋 안 됨) → BuildConfig로 주입된다.
 * 항목 이름은 저장소의 local.properties.example 참고. 바꾸면 다시 빌드해야 반영된다.
 */
public final class ServerConfig {

    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 예: https://temi-domain.duckdns.org (끝의 / 제거) */
    public static final String BASE_URL = stripSlash(BuildConfig.AGENT_BASE_URL);
    /** temi 역할 키. 로그·화면에 그대로 출력하지 말 것 */
    public static final String API_KEY = BuildConfig.AGENT_API_KEY;
    /** /agent/child의 device_id, /ws/temi의 X-Device-Id */
    public static final String DEVICE_ID_VALUE = BuildConfig.TEMI_DEVICE_ID;

    private ServerConfig() {}

    /** 설정에 문제가 있으면 화면에 보여줄 설명, 없으면 null */
    public static String problem() {
        String url = urlProblem();
        if (url != null) return url;
        if (API_KEY.isEmpty()) return "local.properties에 agent.apiKey가 없습니다.";
        if (!DEVICE_ID.matcher(DEVICE_ID_VALUE).matches())
            return "local.properties의 temi.deviceId가 비었거나 형식이 틀립니다(영문·숫자·.·_·- 1~64자).";
        return null;
    }

    /** 서버 주소만 검사 (인증 없는 /health용) */
    public static String urlProblem() {
        if (BASE_URL.isEmpty()) return "local.properties에 agent.baseUrl이 없습니다.";
        if (!BASE_URL.startsWith("https://")) return "agent.baseUrl은 https:// 주소만 허용합니다(키가 평문으로 나가지 않게).";
        return null;
    }

    /** https://host → wss://host/ws/temi */
    public static String controlUrl() {
        return "wss://" + BASE_URL.substring("https://".length()) + "/ws/temi";
    }

    /** 화면 표시용: 키는 앞 4자만 */
    public static String describe() {
        String key = API_KEY.isEmpty() ? "(없음)"
                : API_KEY.substring(0, Math.min(4, API_KEY.length())) + "… (" + API_KEY.length() + "자)";
        return "서버 " + (BASE_URL.isEmpty() ? "(없음)" : BASE_URL)
                + "\n장치 ID " + (DEVICE_ID_VALUE.isEmpty() ? "(없음)" : DEVICE_ID_VALUE)
                + "\n키 " + key;
    }

    private static String stripSlash(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
