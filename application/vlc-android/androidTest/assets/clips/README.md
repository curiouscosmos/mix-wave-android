Synthetic six-second test audio generated with FFmpeg, containing alternating tones.
No discourse recordings or third-party audio are included.

`caption-seek-vbr.mp3` is a 120-second synthetic VBR recording without a Xing index.
Its tone rises by 100 Hz every five seconds, starting at 300 Hz. Noise in the first
minute changes the bitrate so seeking by estimated bitrate misidentifies phrases.
The caption seek test identifies the decoded tone and compares it with caption timing.

    ffmpeg -f lavfi -i 'aevalsrc=0.3*sin(2*PI*(300+100*floor(t/5))*t)+if(lt(t\,60)\,0.015*(random(0)-0.5)\,0):s=48000:d=120' -c:a libmp3lame -q:a 2 -write_xing 0 caption-seek-vbr.mp3

Regenerate with:

    ffmpeg -f lavfi -i "aevalsrc=0.3*sin(2*PI*(440+220*floor(t))*t):s=44100:d=6" -c:a libmp3lame -q:a 4 variable.mp3
    ffmpeg -f lavfi -i "aevalsrc=0.3*sin(2*PI*(440+220*floor(t))*t):s=44100:d=6" -c:a aac -b:a 96k sample.m4a

`speech-24k.mp3` covers decoder priming at a low speech sample rate, which can exceed
the exporter's original 2048-frame padding allowance after resampling to 48 kHz.

    ffmpeg -f lavfi -i sine=frequency=440:sample_rate=24000:duration=6 -c:a libmp3lame -b:a 56k speech-24k.mp3

`visual.mp4` is a two-second SDR H.264 video (one second red, one second blue),
with an unrelated 1800 Hz tone to verify that video export discards source audio.
`rotated.mp4` adds a green upper-left marker and 90-degree display rotation metadata.

    ffmpeg -f lavfi -i "color=c=red:s=320x180:r=12:d=1" -f lavfi -i "color=c=blue:s=320x180:r=12:d=1" -f lavfi -i "sine=frequency=1800:duration=2" -filter_complex "[0:v][1:v]concat=n=2:v=1:a=0[v]" -map "[v]" -map 2:a -c:v libx264 -pix_fmt yuv420p -bf 0 -g 12 -c:a aac visual.mp4
    ffmpeg -i visual.mp4 -vf "drawbox=x=0:y=0:w=80:h=90:color=lime:t=fill" -an -c:v libx264 -pix_fmt yuv420p -bf 0 marked.mp4
    ffmpeg -display_rotation:v:0 90 -i marked.mp4 -c copy rotated.mp4

`hdr.mp4` uses the same synthetic pixels with HLG/BT.2020 signaling to test rejection.

    ffmpeg -i visual.mp4 -c copy -bsf:v h264_metadata=colour_primaries=9:transfer_characteristics=18:matrix_coefficients=9 hdr.mp4
