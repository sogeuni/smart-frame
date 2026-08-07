# Project-specific R8 rules.
#
# The app currently does not use reflection-based models or JavaScript
# interfaces that require additional keep rules. AndroidX, Compose, and
# Firebase provide their own consumer rules.

# MSAL transitively includes Nimbus JOSE, which has optional Tink and
# Bouncy Castle paths for algorithms this app does not use.
# MSAL은 사용하지 않는 선택적 Tink/Bouncy Castle 암호화 경로를 참조하므로,
# R8이 생성한 누락 클래스 경고를 억제한다.
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign$KeyPair
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign
-dontwarn com.google.crypto.tink.subtle.Ed25519Verify
-dontwarn com.google.crypto.tink.subtle.X25519
-dontwarn com.google.crypto.tink.subtle.XChaCha20Poly1305
-dontwarn org.bouncycastle.asn1.ASN1Encodable
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.asn1.x509.AlgorithmIdentifier
-dontwarn org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
-dontwarn org.bouncycastle.cert.X509CertificateHolder
-dontwarn org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.openssl.PEMKeyPair
-dontwarn org.bouncycastle.openssl.PEMParser
-dontwarn org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
