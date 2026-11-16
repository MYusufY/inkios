package tech.tachion.inkios.net;

import android.content.Context;

import tech.tachion.inkios.core.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class Net {
    private static SSLSocketFactory sFactory;
    private static final String UA = "Inkios/0.1 (e-reader; +https://tachion.tech)";

    private Net() {}

    public static synchronized void init(Context c) {
        if (sFactory != null) return;
        try {
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
            extra.load(null, null);
            try (InputStream in = c.getResources().openRawResource(R.raw.extra_roots)) {
                Collection<? extends java.security.cert.Certificate> certs =
                        CertificateFactory.getInstance("X.509").generateCertificates(in);
                int i = 0;
                for (java.security.cert.Certificate cert : certs) extra.setCertificateEntry("extra" + i++, cert);
            }
            TrustManagerFactory ext = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            ext.init(extra);
            final X509TrustManager a = first(sys.getTrustManagers()), b = first(ext.getTrustManagers());
            X509TrustManager both = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    a.checkClientTrusted(chain, auth);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        a.checkServerTrusted(chain, auth);
                    } catch (CertificateException e) {
                        b.checkServerTrusted(chain, auth);
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> l = new ArrayList<>();
                    for (X509Certificate x : a.getAcceptedIssuers()) l.add(x);
                    for (X509Certificate x : b.getAcceptedIssuers()) l.add(x);
                    return l.toArray(new X509Certificate[0]);
                }
            };
            SSLContext ctx;
            try {
                java.security.Provider p = org.conscrypt.Conscrypt.newProvider();
                ctx = SSLContext.getInstance("TLS", p);
            } catch (Throwable t) {
                ctx = SSLContext.getInstance("TLS");
            }
            ctx.init(null, new TrustManager[]{both}, null);
            sFactory = new ModernTls(ctx.getSocketFactory());
        } catch (Exception e) {
            sFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private static X509TrustManager first(TrustManager[] tms) {
        for (TrustManager t : tms) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }

    public static HttpURLConnection secure(HttpURLConnection c) {
        if (c instanceof HttpsURLConnection && sFactory != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(sFactory);
        }
        return c;
    }

    public static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection && sFactory != null) {
                ((HttpsURLConnection) c).setSSLSocketFactory(sFactory);
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code + " for " + url);
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    public static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static final class ModernTls extends SSLSocketFactory {
        private final SSLSocketFactory d;

        ModernTls(SSLSocketFactory d) { this.d = d; }

        private Socket on(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> want = new ArrayList<>();
                for (String p : ss.getSupportedProtocols()) {
                    if (p.equals("TLSv1.3") || p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1")) want.add(p);
                }
                ss.setEnabledProtocols(want.toArray(new String[0]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean ac) throws IOException { return on(d.createSocket(s, h, p, ac)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return on(d.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException { return on(d.createSocket(h, p, la, lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return on(d.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException { return on(d.createSocket(a, p, la, lp)); }
    }
}
