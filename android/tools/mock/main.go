package main

// Udawana centrala MiCall do testu aplikacji w emulatorze (GitHub Actions).
// Strona / przekazuje sesję aplikacji, /api/push/stream wysyła zdarzenia na żądanie (/trigger?e=...).

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

var (
	mu    sync.Mutex
	subs  = map[chan string]bool{}
	seq   = 100
	reqs  []string
	start = time.Now()
	cmd   string
)

func note(s string) {
	mu.Lock()
	reqs = append(reqs, fmt.Sprintf("%6.1fs %s", time.Since(start).Seconds(), s))
	mu.Unlock()
	log.Println(s)
}

func send(data string) {
	mu.Lock()
	defer mu.Unlock()
	seq++
	for c := range subs {
		select {
		case c <- fmt.Sprintf("id: %d\ndata: %s\n\n", seq, data):
		default:
		}
	}
}

const page = `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="background:#0B0616;color:#fff;font:18px sans-serif;padding:20px">
<h2 id=t>MiCall test</h2><pre id=o style="white-space:pre-wrap;font-size:13px"></pre>
<script>
const o = document.getElementById('o'), q = new URLSearchParams(location.search);
const log = s => { o.textContent += s + '\n'; fetch('/note?s=' + encodeURIComponent(s)); };
window.MiCallNativeVis = v => log('vis ' + v + ' ' + (MiCallAndroid.bubbleCid() || ''));
(async () => {
  if (q.get('bubble')) document.getElementById('t').textContent = 'Dymek: ' + q.get('bubble');
  for (let i = 0; i < 100 && !window.__mcn; i++) await new Promise(r => setTimeout(r, 100));
  log('nonce ' + (window.__mcn ? 'ok' : 'BRAK') + ' wersja ' + MiCallAndroid.version() + ' urzadzenie ' + MiCallAndroid.device());
  if (!q.get('bubble')) MiCallAndroid.setToken(window.__mcn, 'test-token');
  MiCallAndroid.setToken('zly', 'atak'); // bez sekretu — ma zostać zignorowane
  log('status ' + MiCallAndroid.status());
  if (q.get('open')) log('open ' + q.get('open') + ' answer=' + q.get('answer'));
  window.testBubble = () => log('bubble ' + MiCallAndroid.bubble(window.__mcn, JSON.stringify({c: 'c1', ti: 'Ola', i: '/a.png', a: 'u1'})));
  if (!q.get('bubble')) setInterval(async () => { const c = await (await fetch('/cmd')).text(); if (c === 'bubble') testBubble(); }, 1500);
})();
</script>`

func avatar(w http.ResponseWriter, r *http.Request) {
	img := image.NewRGBA(image.Rect(0, 0, 256, 256))
	for y := 0; y < 256; y++ {
		for x := 0; x < 256; x++ {
			img.Set(x, y, color.RGBA{uint8(x), 80, uint8(255 - y), 255})
		}
	}
	var b bytes.Buffer
	png.Encode(&b, img)
	w.Header().Set("Content-Type", "image/png")
	w.Write(b.Bytes())
}

var events = map[string]string{
	"msg1":    `{"t":"msg","c":"c1","ti":"Ola","n":"Ola","a":"u1","i":"/a.png","b":"Hej! Co tam? 😊","mid":"m1","ts":%d,"dm":true}`,
	"msg2":    `{"t":"msg","c":"c1","ti":"Ola","n":"Ola","a":"u1","i":"/a.png","b":"Wpadniesz wieczorem?","mid":"m2","ts":%d,"dm":true}`,
	"grp":     `{"t":"msg","c":"c3","ti":"Kuba","n":"Kuba","a":"u2","i":"","b":"@Ty patrz na to","cv":"#ogólny • Galaktyka","mid":"m3","ts":%d,"dm":false}`,
	"call":    `{"t":"call","c":"c2","ti":"Kuba","n":"Kuba","a":"u2","i":"/a.png","b":"📞 Dzwoni do Ciebie w MiCall","ts":%d}`,
	"callend": `{"t":"callend","c":"c2","x":%d}`,
	"missed":  `{"t":"missed","c":"c2","ti":"Kuba","n":"Kuba","a":"u2","i":"/a.png","b":"📵 Nieodebrane połączenie","ts":%d}`,
	"clear":   `{"t":"clear","c":"c1","x":%d}`,
}

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		note("GET " + r.URL.String())
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, page)
	})
	http.HandleFunc("/a.png", avatar)
	http.HandleFunc("/note", func(w http.ResponseWriter, r *http.Request) { note("js: " + r.URL.Query().Get("s")) })
	http.HandleFunc("/log", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		io.WriteString(w, strings.Join(reqs, "\n")+"\n")
	})
	http.HandleFunc("/cmd", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		c := cmd
		cmd = ""
		mu.Unlock()
		io.WriteString(w, c)
	})
	http.HandleFunc("/trigger", func(w http.ResponseWriter, r *http.Request) {
		e := r.URL.Query().Get("e")
		if strings.HasPrefix(e, "cmd-") {
			mu.Lock()
			cmd = strings.TrimPrefix(e, "cmd-")
			mu.Unlock()
		}
		if f, ok := events[e]; ok {
			send(fmt.Sprintf(f, time.Now().UnixMilli()))
			note("trigger " + e)
		}
		mu.Lock()
		n := len(subs)
		mu.Unlock()
		fmt.Fprintf(w, "ok %d\n", n)
	})
	http.HandleFunc("/api/info", func(w http.ResponseWriter, r *http.Request) {
		note("GET /api/info")
		io.WriteString(w, `{"app":"MikolChat","version":"test"}`)
	})
	http.HandleFunc("/api/channels/", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		note(r.Method + " " + r.URL.Path + " auth=" + r.Header.Get("Authorization") + " " + string(b))
		io.WriteString(w, `{"ok":true}`)
	})
	http.HandleFunc("/api/push/stream", func(w http.ResponseWriter, r *http.Request) {
		note("STREAM " + r.URL.RawQuery + " auth=" + r.Header.Get("Authorization"))
		if r.Header.Get("Authorization") != "Bearer test-token" {
			http.Error(w, `{"error":"x"}`, 401)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		io.WriteString(w, "data: {\"t\":\"hello\",\"v\":\"test\"}\n\n")
		fl.Flush()
		c := make(chan string, 20)
		mu.Lock()
		subs[c] = true
		mu.Unlock()
		defer func() { mu.Lock(); delete(subs, c); mu.Unlock(); note("STREAM koniec") }()
		t := time.NewTicker(20 * time.Second)
		for {
			select {
			case <-r.Context().Done():
				return
			case s := <-c:
				io.WriteString(w, s)
				fl.Flush()
			case <-t.C:
				io.WriteString(w, ": p\n\n")
				fl.Flush()
			}
		}
	})
	log.Fatal(http.ListenAndServe("127.0.0.1:8080", nil))
}
