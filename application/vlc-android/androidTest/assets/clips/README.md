Synthetic six-second test audio generated with FFmpeg, containing alternating tones.
No discourse recordings or third-party audio are included.

Regenerate with:

    ffmpeg -f lavfi -i "aevalsrc=0.3*sin(2*PI*(440+220*floor(t))*t):s=44100:d=6" -c:a libmp3lame -q:a 4 variable.mp3
    ffmpeg -f lavfi -i "aevalsrc=0.3*sin(2*PI*(440+220*floor(t))*t):s=44100:d=6" -c:a aac -b:a 96k sample.m4a
