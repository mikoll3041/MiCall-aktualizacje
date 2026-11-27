package main

// Podpis aplikacji na Androida (APK Signature Scheme v2) — robi go sama centrala.
// GitHub buduje niepodpisany plik APK, a centrala podpisuje go własnym kluczem, który nigdy nie opuszcza serwera.
// Dzięki temu każda nowa wersja ma ten sam podpis i telefon instaluje ją jako aktualizację.
// Ten plik nie zależy od reszty MiCall (kopia leży też w repozytorium aktualizacji, do testu w GitHub Actions).

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"math/big"
	"os"
	"time"
)

const apkSigMagic = "APK Sig Block 42"

func apkU32(b *bytes.Buffer, v uint32) { binary.Write(b, binary.LittleEndian, v) }
func apkU64(b *bytes.Buffer, v uint64) { binary.Write(b, binary.LittleEndian, v) }
func apkLP(b *bytes.Buffer, p []byte) { apkU32(b, uint32(len(p))); b.Write(p) }

// apkEOCD — położenie końcowego rekordu ZIP (End of Central Directory).
func apkEOCD(z []byte) int {
	for i := len(z) - 22; i >= 0 && i >= len(z)-22-65535; i-- {
		if binary.LittleEndian.Uint32(z[i:]) == 0x06054b50 && int(binary.LittleEndian.Uint16(z[i+20:])) == len(z)-i-22 {
			return i
		}
	}
	return -1
}

// apkDigest — skrót v2: SHA-256 z kawałków po 1 MB (zawartość ZIP, katalog centralny, koniec ZIP).
func apkDigest(secs ...[]byte) []byte {
	var all [][]byte
	for _, s := range secs {
		for off := 0; off < len(s); off += 1 << 20 {
			end := off + 1<<20
			if end > len(s) {
				end = len(s)
			}
			h := sha256.New()
			var hd bytes.Buffer
			hd.WriteByte(0xa5)
			apkU32(&hd, uint32(end-off))
			h.Write(hd.Bytes())
			h.Write(s[off:end])
			all = append(all, h.Sum(nil))
		}
	}
	h := sha256.New()
	var hd bytes.Buffer
	hd.WriteByte(0x5a)
	apkU32(&hd, uint32(len(all)))
	h.Write(hd.Bytes())
	for _, d := range all {
		h.Write(d)
	}
	return h.Sum(nil)
}

// signAPKv2 — zwraca podpisany APK (plik musi być wcześniej wyrównany przez zipalign).
func signAPKv2(apk []byte, key *rsa.PrivateKey, cert []byte) ([]byte, error) {
	eocd := apkEOCD(apk)
	if eocd < 0 {
		return nil, errors.New("to nie jest plik APK")
	}
	cdSize := int(binary.LittleEndian.Uint32(apk[eocd+12:]))
	cdOff := int(binary.LittleEndian.Uint32(apk[eocd+16:]))
	if cdOff+cdSize != eocd {
		return nil, errors.New("nieobsługiwany układ pliku APK")
	}
	if cdOff >= 32 && string(apk[cdOff-16:cdOff]) == apkSigMagic {
		return nil, errors.New("APK jest już podpisany")
	}
	sec1, sec3, sec4 := apk[:cdOff], apk[cdOff:eocd], apk[eocd:]
	digest := apkDigest(sec1, sec3, sec4)
	pub, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, err
	}
	const alg = 0x0103 // RSASSA-PKCS1-v1_5 + SHA-256
	var one, digests, certs, sd bytes.Buffer
	apkU32(&one, alg)
	apkLP(&one, digest)
	apkLP(&digests, one.Bytes())
	apkLP(&certs, cert)
	apkLP(&sd, digests.Bytes())
	apkLP(&sd, certs.Bytes())
	apkLP(&sd, nil) // bez dodatkowych atrybutów
	hs := sha256.Sum256(sd.Bytes())
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hs[:])
	if err != nil {
		return nil, err
	}
	var sg, sigs, signer, signers, v2 bytes.Buffer
	apkU32(&sg, alg)
	apkLP(&sg, sig)
	apkLP(&sigs, sg.Bytes())
	apkLP(&signer, sd.Bytes())
	apkLP(&signer, sigs.Bytes())
	apkLP(&signer, pub)
	apkLP(&signers, signer.Bytes())
	apkLP(&v2, signers.Bytes())
	val := v2.Bytes()
	pairLen := uint64(4 + len(val))
	size := 8 + pairLen + 8 + 16
	var blk bytes.Buffer
	apkU64(&blk, size)
	apkU64(&blk, pairLen)
	apkU32(&blk, 0x7109871a)
	blk.Write(val)
	apkU64(&blk, size)
	blk.WriteString(apkSigMagic)

	out := make([]byte, 0, len(apk)+blk.Len())
	out = append(out, sec1...)
	out = append(out, blk.Bytes()...)
	out = append(out, sec3...)
	end := append([]byte{}, sec4...)
	binary.LittleEndian.PutUint32(end[16:], uint32(cdOff+blk.Len()))
	return append(out, end...), nil
}

// apkKey — klucz podpisu aplikacji: tworzony raz i trzymany w danych centrali (plik PEM: klucz + certyfikat).
func apkKey(path string) (*rsa.PrivateKey, []byte, error) {
	if b, err := os.ReadFile(path); err == nil {
		var key *rsa.PrivateKey
		var cert []byte
		for {
			var blk *pem.Block
			blk, b = pem.Decode(b)
			if blk == nil {
				break
			}
			switch blk.Type {
			case "PRIVATE KEY":
				if k, err := x509.ParsePKCS8PrivateKey(blk.Bytes); err == nil {
					key, _ = k.(*rsa.PrivateKey)
				}
			case "CERTIFICATE":
				cert = blk.Bytes
			}
		}
		if key != nil && cert != nil {
			return key, cert, nil
		}
		return nil, nil, errors.New("uszkodzony klucz podpisu aplikacji")
	}
	key, err := rsa.GenerateKey(rand.Reader, 3072)
	if err != nil {
		return nil, nil, err
	}
	sn, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 63))
	tpl := &x509.Certificate{
		SerialNumber: sn,
		Subject:      pkix.Name{CommonName: "MiCall", Organization: []string{"MiCall"}, Country: []string{"PL"}},
		NotBefore:    time.Now().Add(-24 * time.Hour),
		NotAfter:     time.Now().AddDate(50, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	cert, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	der, _ := x509.MarshalPKCS8PrivateKey(key)
	var buf bytes.Buffer
	pem.Encode(&buf, &pem.Block{Type: "PRIVATE KEY", Bytes: der})
	pem.Encode(&buf, &pem.Block{Type: "CERTIFICATE", Bytes: cert})
	if err := os.WriteFile(path, buf.Bytes(), 0600); err != nil {
		return nil, nil, err
	}
	return key, cert, nil
}
