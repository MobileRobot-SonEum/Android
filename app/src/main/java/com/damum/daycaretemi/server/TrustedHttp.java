package com.damum.daycaretemi.server;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.damum.daycaretemi.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * 에이전트 서버(https / wss)용 OkHttpClient. 앱 전체에 하나.
 *
 * <p>temi는 Android 6(API 23)이라 시스템 신뢰 저장소에 Let's Encrypt의 ISRG Root X1이 없다(7.1.1부터 포함).
 * network_security_config는 API 24부터라 못 쓰므로, <b>시스템 저장소 + res/raw/isrg_root_x1.pem</b>을 함께 신뢰하는
 * TrustManager를 직접 만든다. 호스트 이름 검증은 OkHttp 기본값 그대로 둔다(끄지 않음).
 *
 * <p>2026-11-17 확인: 서버 체인 = leaf ← YE2 ← Root YE ← ISRG Root X2 ← ISRG Root X1(교차 서명). X1만으로 검증됨.
 */
public final class TrustedHttp {

    private static final String TAG = "TrustedHttp";

    /** ISRG Root X1 SHA-256 지문 (https://letsencrypt.org/certificates/). 리소스가 바뀌었으면 로드를 거부한다. */
    static final String ISRG_ROOT_X1_SHA256 =
            "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6";

    private static OkHttpClient client;

    private TrustedHttp() {}

    /** @throws IllegalStateException 인증서 리소스를 못 읽었을 때 (호출한 쪽에서 TLS 오류로 표시) */
    public static synchronized OkHttpClient get(Context context) {
        if (client == null) {
            try {
                client = build(context.getApplicationContext());
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, "TLS 설정 실패", e);
                throw new IllegalStateException("TLS 설정 실패: " + e.getMessage(), e);
            }
        }
        return client;
    }

    private static OkHttpClient build(Context context) throws GeneralSecurityException, IOException {
        X509TrustManager system = trustManagerFor(null);   // null = 시스템 기본 저장소

        KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
        extra.load(null, null);
        extra.setCertificateEntry("isrg_root_x1", loadIsrgRootX1(context));

        X509TrustManager trust = new SystemPlusExtraTrustManager(system, trustManagerFor(extra));
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{trust}, null);

        return new OkHttpClient.Builder()
                .sslSocketFactory(ssl.getSocketFactory(), trust)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    private static X509TrustManager trustManagerFor(KeyStore keyStore) throws GeneralSecurityException {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(keyStore);
        for (TrustManager tm : f.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("X509TrustManager 없음");
    }

    /** PEM 앞의 설명 줄은 무시하고 BEGIN~END만 읽은 뒤 지문을 확인한다. */
    static X509Certificate loadIsrgRootX1(Context context) throws IOException, GeneralSecurityException {
        String pem;
        try (InputStream in = context.getResources().openRawResource(R.raw.isrg_root_x1)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            pem = new String(out.toByteArray(), Charset.forName("UTF-8"));
        }
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";
        int b = pem.indexOf(begin);
        int e = pem.indexOf(end);
        if (b < 0 || e < b) throw new CertificateException("isrg_root_x1.pem: PEM 형식이 아닙니다");
        byte[] der = Base64.decode(pem.substring(b + begin.length(), e).replaceAll("\\s", ""), Base64.DEFAULT);

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        String fp = hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        if (!ISRG_ROOT_X1_SHA256.equals(fp)) {
            throw new CertificateException("isrg_root_x1.pem 지문 불일치: " + fp);
        }
        return cert;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) sb.append(String.format(Locale.ROOT, "%02X", x));
        return sb.toString();
    }

    /** 시스템 저장소로 먼저 검증하고, 실패하면 추가 루트(ISRG Root X1)로 검증한다. 둘 다 실패하면 거부. */
    private static final class SystemPlusExtraTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager extra;

        SystemPlusExtraTrustManager(X509TrustManager system, X509TrustManager extra) {
            this.system = system;
            this.extra = extra;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemFailure) {
                try {
                    extra.checkServerTrusted(chain, authType);
                } catch (CertificateException extraFailure) {
                    extraFailure.addSuppressed(systemFailure);
                    throw extraFailure;
                }
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = system.getAcceptedIssuers();
            X509Certificate[] b = extra.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }
}
