package com.docreader.firma;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;

/**
 * Firma CMS separada (SignedData) para PDF con SubFilter ETSI.CAdES.detached: nivel PAdES B-B.
 * Atributos firmados: contentType, messageDigest y signingCertificateV2 (sin signingTime,
 * que en PAdES va en la entrada /M del diccionario de firma).
 */
final class Cms {
    private static final String ID_DATA = "1.2.840.113549.1.7.1";
    private static final String ID_SIGNED_DATA = "1.2.840.113549.1.7.2";
    private static final String ATR_CONTENT_TYPE = "1.2.840.113549.1.9.3";
    private static final String ATR_MESSAGE_DIGEST = "1.2.840.113549.1.9.4";
    private static final String ATR_SIGNING_CERT_V2 = "1.2.840.113549.1.9.16.2.47";
    private static final String SHA256 = "2.16.840.1.101.3.4.2.1";
    private static final String RSA = "1.2.840.113549.1.1.1";
    private static final String ECDSA_SHA256 = "1.2.840.10045.4.3.2";

    private Cms() { }

    static byte[] firmar(InputStream contenido, Identidad id) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = contenido.read(buf)) > 0; ) md.update(buf, 0, n);
            byte[] resumen = md.digest();

            X509Certificate cert = id.cadena[0];
            byte[] certDer = cert.getEncoded();
            byte[] emisor = Der.emisor(cert.getTBSCertificate());
            byte[] serie = Der.entero(cert.getSerialNumber());
            byte[] algResumen = Der.seq(Der.oid(SHA256));

            byte[] essCertIdV2 = Der.seq(
                    Der.octetos(MessageDigest.getInstance("SHA-256").digest(certDer)),
                    Der.seq(Der.seq(Der.tlv(0xA4, emisor)), serie));
            byte[] atributos = Der.set(
                    Der.seq(Der.oid(ATR_CONTENT_TYPE), Der.set(Der.oid(ID_DATA))),
                    Der.seq(Der.oid(ATR_MESSAGE_DIGEST), Der.set(Der.octetos(resumen))),
                    Der.seq(Der.oid(ATR_SIGNING_CERT_V2), Der.set(Der.seq(Der.seq(essCertIdV2)))));

            boolean ec = "EC".equalsIgnoreCase(id.clave.getAlgorithm());
            Signature s = Signature.getInstance(ec ? "SHA256withECDSA" : "SHA256withRSA");
            s.initSign(id.clave);
            s.update(atributos); // se firma el SET con etiqueta 0x31
            byte[] firma = s.sign();

            byte[] atributosImplicitos = atributos.clone();
            atributosImplicitos[0] = (byte) 0xA0; // [0] IMPLICIT dentro de SignerInfo
            byte[] algFirma = ec ? Der.seq(Der.oid(ECDSA_SHA256)) : Der.seq(Der.oid(RSA), Der.nulo());

            byte[] signerInfo = Der.seq(
                    Der.entero(1),
                    Der.seq(emisor, serie),
                    algResumen,
                    atributosImplicitos,
                    algFirma,
                    Der.octetos(firma));

            byte[][] certs = new byte[id.cadena.length][];
            for (int i = 0; i < certs.length; i++) certs[i] = id.cadena[i].getEncoded();

            byte[] signedData = Der.seq(
                    Der.entero(1),
                    Der.set(algResumen),
                    Der.seq(Der.oid(ID_DATA)),
                    Der.tlv(0xA0, Der.concat(certs)),
                    Der.set(signerInfo));
            return Der.seq(Der.oid(ID_SIGNED_DATA), Der.tlv(0xA0, signedData));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("No se pudo firmar con el certificado: " + e.getMessage(), e);
        }
    }
}
