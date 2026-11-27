package main

// Test podpisu w GitHub Actions: ten sam kod, którym podpisuje centrala (apksign.go), sprawdzany przez apksigner.
//   go run . <niepodpisany.apk> <podpisany.apk> <klucz.pem>

import (
	"fmt"
	"os"
)

func main() {
	in, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	key, cert, err := apkKey(os.Args[3])
	if err != nil {
		panic(err)
	}
	out, err := signAPKv2(in, key, cert)
	if err != nil {
		panic(err)
	}
	if err := os.WriteFile(os.Args[2], out, 0644); err != nil {
		panic(err)
	}
	fmt.Println("podpisano:", os.Args[2], len(out), "bajtów")
}
