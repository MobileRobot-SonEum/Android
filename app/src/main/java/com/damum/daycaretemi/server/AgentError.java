package com.damum.daycaretemi.server;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

/** 서버 요청 실패를 종류별로 나눈 것. 화면에 {@link #userMessage()}로 보여준다. */
public final class AgentError {

    public enum Kind {
        /** local.properties 설정 없음/잘못됨 */
        CONFIG,
        /** 도메인 이름을 못 찾음 (DNS, 와이파이 없음) */
        DNS,
        /** 인증서·TLS 핸드셰이크 실패 */
        TLS,
        /** 401: 키 없음/틀림 */
        UNAUTHORIZED,
        /** 403: temi 키로 못 쓰는 API (코드 버그) 또는 WebSocket 접속 거부 */
        FORBIDDEN,
        /** 연결·읽기 시간 초과 */
        TIMEOUT,
        /** 5xx: 서버 오류·재시작 중·프록시 오류 */
        SERVER,
        /** 그 밖의 4xx (422 형식 오류 등) */
        CLIENT,
        /** 연결 거부·끊김 등 그 밖의 네트워크 오류 */
        NETWORK,
        /** 200인데 JSON이 예상과 다름 */
        BAD_RESPONSE
    }

    public final Kind kind;
    /** HTTP 응답이 있었으면 그 코드, 없으면 0 */
    public final int httpCode;
    /** 개발자용 상세 (예외 이름·메시지, 응답 본문 앞부분). 아이 발화는 들어가지 않음 */
    public final String detail;

    AgentError(Kind kind, int httpCode, String detail) {
        this.kind = kind;
        this.httpCode = httpCode;
        this.detail = detail == null ? "" : detail;
    }

    /** 네트워크 계열이라 아이에게 대체 답변을 말해야 하는 경우 (설정·코드 문제는 제외) */
    public boolean shouldSpeakFallback() {
        switch (kind) {
            case DNS: case TLS: case TIMEOUT: case SERVER: case NETWORK: case BAD_RESPONSE: return true;
            default: return false;
        }
    }

    public String userMessage() {
        switch (kind) {
            case CONFIG: return "설정 필요: " + detail;
            case DNS: return "DNS 실패 — 서버 주소를 찾을 수 없습니다. 와이파이와 agent.baseUrl을 확인하세요.";
            case TLS: return "TLS 실패 — 인증서 확인에 실패했습니다. temi 날짜·시간과 인증서 설정을 확인하세요.";
            case UNAUTHORIZED: return "401 — API 키가 없거나 틀립니다. agent.apiKey를 확인하세요(재시도 안 함).";
            case FORBIDDEN: return "403 — temi 키로 쓸 수 없는 요청입니다.";
            case TIMEOUT: return "시간 초과 — 서버가 제때 응답하지 않았습니다.";
            case SERVER: return "서버 오류 " + httpCode + " — 서버 재시작 중이거나 서버 문제입니다.";
            case CLIENT: return "요청 오류 " + httpCode;
            case BAD_RESPONSE: return "응답 형식 오류 — 서버 응답을 읽을 수 없습니다.";
            default: return "네트워크 오류 — 서버에 연결할 수 없습니다.";
        }
    }

    @Override
    public String toString() {
        return kind + (httpCode > 0 ? " " + httpCode : "") + (detail.isEmpty() ? "" : " (" + detail + ")");
    }

    static AgentError config(String problem) {
        return new AgentError(Kind.CONFIG, 0, problem);
    }

    static AgentError badResponse(String detail) {
        return new AgentError(Kind.BAD_RESPONSE, 200, detail);
    }

    /** 예외의 원인 사슬을 따라가며 DNS / TLS / 시간 초과를 구분한다. */
    static AgentError fromThrowable(Throwable error) {
        String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) return new AgentError(Kind.DNS, 0, detail);
            if (t instanceof SSLException || t instanceof CertificateException)
                return new AgentError(Kind.TLS, 0, detail);
            // SocketTimeoutException 포함. OkHttp callTimeout은 InterruptedIOException("timeout")
            if (t instanceof InterruptedIOException) return new AgentError(Kind.TIMEOUT, 0, detail);
            if (t instanceof IllegalStateException && String.valueOf(t.getMessage()).startsWith("TLS 설정 실패"))
                return new AgentError(Kind.TLS, 0, detail);
        }
        return new AgentError(error instanceof IOException ? Kind.NETWORK : Kind.BAD_RESPONSE, 0, detail);
    }

    static AgentError fromHttp(int code, String bodyHead) {
        Kind kind;
        if (code == 401) kind = Kind.UNAUTHORIZED;
        else if (code == 403) kind = Kind.FORBIDDEN;
        else if (code >= 500) kind = Kind.SERVER;
        else kind = Kind.CLIENT;
        return new AgentError(kind, code, bodyHead);
    }
}
