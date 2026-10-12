package com.fixsssator.netscope.proxy;

import android.content.Context;
import android.util.Base64;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * Owns the local certificate authority used to decrypt HTTPS traffic.
 * The CA is created once on the device, never leaves it, and signs one short-lived leaf certificate per host.
 */
public final class CertManager {
    private static final char[] PASSWORD = "netscope".toCharArray();
    private static final long DAY = 24L * 3600 * 1000;
    private static CertManager instance;

    public static synchronized CertManager get(Context ctx) {
        if (instance == null) instance = new CertManager(ctx.getApplicationContext());
        return instance;
    }

    private final File dir;
    private X509Certificate caCert;
    private PrivateKey caKey;
    private KeyPair leafKeys;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SSLSocketFactory> factories = new LinkedHashMap<String, SSLSocketFactory>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SSLSocketFactory> eldest) {
            return size() > 256;
        }
    };

    private CertManager(Context ctx) {
        dir = new File(ctx.getFilesDir(), "ca");
    }

    /** True when a CA exists (loads it from disk if needed). Never generates a new one. */
    public synchronized boolean isReady() {
        if (caCert != null) return true;
        return filesExist() && load();
    }

    /** Loads the CA or creates a new one (RSA key generation takes a second or two - call off the main thread). */
    public synchronized void ensure() throws Exception {
        if (caCert != null) return;
        if (filesExist() && load()) return;
        generate();
    }

    /** Throws the old CA away and creates a new one. The user has to install the new certificate again. */
    public synchronized void regenerate() throws Exception {
        factories.clear();
        caCert = null;
        caKey = null;
        leafKeys = null;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        generate();
    }

    /** True when this exact CA is present in the Android user certificate store. */
    public synchronized boolean isInstalled() {
        try {
            if (!isReady()) return false;
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            byte[] mine = caCert.getEncoded();
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!alias.startsWith("user:")) continue;
                Certificate c = ks.getCertificate(alias);
                if (c != null && Arrays.equals(c.getEncoded(), mine)) return true;
            }
        } catch (Exception ignored) {
            // treat any failure as "not installed"
        }
        return false;
    }

    public synchronized String caPem() throws Exception {
        ensure();
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.encodeToString(caCert.getEncoded(), Base64.DEFAULT)
                + "-----END CERTIFICATE-----\n";
    }

    /** Socket factory (server side) presenting a certificate for {@code host}, signed by the NetScope CA. */
    public synchronized SSLSocketFactory factoryFor(String host) throws Exception {
        ensure();
        SSLSocketFactory f = factories.get(host);
        if (f != null) return f;
        X509Certificate leaf = leafFor(host);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("leaf", leafKeys.getPrivate(), PASSWORD, new Certificate[]{leaf, caCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        f = ctx.getSocketFactory();
        factories.put(host, f);
        return f;
    }

    // ---------------------------------------------------------------- generation

    private void generate() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, random);
        KeyPair caPair = gen.generateKeyPair();
        KeyPair leaf = gen.generateKeyPair();

        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, "NetScope CA")
                .addRDN(BCStyle.O, "NetScope")
                .build();
        long now = System.currentTimeMillis();
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                subject, serial(), new Date(now - DAY), new Date(now + 3650 * DAY), subject, caPair.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        b.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(caPair.getPublic()));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));

        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        write("ca.der", cert.getEncoded());
        write("ca.key", caPair.getPrivate().getEncoded());
        write("leaf.key", leaf.getPrivate().getEncoded());
        write("leaf.pub", leaf.getPublic().getEncoded());

        caCert = cert;
        caKey = caPair.getPrivate();
        leafKeys = leaf;
        factories.clear();
    }

    private X509Certificate leafFor(String host) throws Exception {
        X500Name issuer = new JcaX509CertificateHolder(caCert).getSubject();
        String cn = host.length() > 64 ? host.substring(0, 64) : host;
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, cn)
                .addRDN(BCStyle.O, "NetScope")
                .build();
        long now = System.currentTimeMillis();
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        // Chrome rejects leaf certificates valid for more than 398 days.
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                issuer, serial(), new Date(now - DAY), new Date(now + 300 * DAY), subject, leafKeys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(subjectAltName(host)));
        b.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(caCert));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKey);
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    private static GeneralName subjectAltName(String host) {
        boolean ip = host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || host.indexOf(':') >= 0;
        return new GeneralName(ip ? GeneralName.iPAddress : GeneralName.dNSName, host);
    }

    private BigInteger serial() {
        return new BigInteger(63, random).add(BigInteger.ONE);
    }

    // ---------------------------------------------------------------- persistence

    private boolean filesExist() {
        return new File(dir, "ca.der").exists() && new File(dir, "ca.key").exists()
                && new File(dir, "leaf.key").exists() && new File(dir, "leaf.pub").exists();
    }

    private boolean load() {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (FileInputStream in = new FileInputStream(new File(dir, "ca.der"))) {
                caCert = (X509Certificate) cf.generateCertificate(in);
            }
            KeyFactory kf = KeyFactory.getInstance("RSA");
            caKey = kf.generatePrivate(new PKCS8EncodedKeySpec(read("ca.key")));
            PrivateKey leafPriv = kf.generatePrivate(new PKCS8EncodedKeySpec(read("leaf.key")));
            PublicKey leafPub = kf.generatePublic(new X509EncodedKeySpec(read("leaf.pub")));
            leafKeys = new KeyPair(leafPub, leafPriv);
            return true;
        } catch (Exception e) {
            caCert = null;
            caKey = null;
            leafKeys = null;
            return false;
        }
    }

    private void write(String name, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        }
    }

    private byte[] read(String name) throws IOException {
        File f = new File(dir, name);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) throw new IOException("short read: " + name);
                off += r;
            }
        }
        return data;
    }
}
