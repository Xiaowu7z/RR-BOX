import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;

/** Uses the existing APK signer without exporting its private key. Run with JDK 17 source launch. */
class SignRuleManifest {
    private static final String PIN = "fe1368cf16ee9e8b56199655d0b1e2606a6ec9b8f3d4ac5e16e8cf66e180d816";

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 3 && arguments[0].equals("sign")) {
            byte[] payload = Files.readAllBytes(Path.of(arguments[1]));
            if (payload.length == 0 || payload.length > 65536) throw new IllegalArgumentException("Manifest payload size");
            KeyStore store = KeyStore.getInstance(Path.of(required("RR_KEYSTORE_PATH")).toFile(), required("RR_KEYSTORE_PASS").toCharArray());
            String alias = required("RR_KEY_ALIAS");
            X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
            checkCertificate(certificate.getEncoded());
            PrivateKey key = (PrivateKey) store.getKey(alias, required("RR_KEY_PASS").toCharArray());
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(payload);
            String envelope = "{\"schemaVersion\":1,\"payload\":\"" + encode(payload)
                    + "\",\"signature\":\"" + encode(signature.sign())
                    + "\",\"certificate\":\"" + encode(certificate.getEncoded()) + "\"}\n";
            Files.writeString(Path.of(arguments[2]), envelope, StandardCharsets.UTF_8);
        } else if (arguments.length == 4 && arguments[0].equals("verify")) {
            byte[] payload = Files.readAllBytes(Path.of(arguments[1]));
            byte[] certificateBytes = Files.readAllBytes(Path.of(arguments[2]));
            byte[] signed = Files.readAllBytes(Path.of(arguments[3]));
            X509Certificate certificate = checkCertificate(certificateBytes);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(payload);
            if (!verifier.verify(signed)) throw new SecurityException("Rule manifest signature failed");
        } else {
            throw new IllegalArgumentException("Usage: sign payload envelope | verify payload certificate signature");
        }
    }

    private static X509Certificate checkCertificate(byte[] bytes) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!PIN.equals(actual)) throw new SecurityException("Rule signer is not the original RRBOX APK signer");
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(bytes));
        if (!certificate.getPublicKey().getAlgorithm().equals("RSA")) throw new SecurityException("Expected RSA signing key");
        return certificate;
    }

    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
