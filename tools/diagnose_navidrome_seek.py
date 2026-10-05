"""Compare actual transcoded seek responses; credentials are environment-only."""
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import urllib.parse
import urllib.request
import uuid


def request(endpoint, read_limit=None, **params):
    salt = uuid.uuid4().hex
    params.update(u=os.environ['ND_DIAGNOSTIC_USER'],
                  t=hashlib.md5((os.environ['ND_DIAGNOSTIC_PASSWORD'] + salt).encode()).hexdigest(),
                  s=salt, v='1.16.1', c='MyBichordDiagnostic', f='json')
    url = os.environ['ND_DIAGNOSTIC_SERVER'].rstrip('/') + '/rest/' + endpoint
    req = urllib.request.Request(url + '?' + urllib.parse.urlencode(params),
                                 headers={'User-Agent': 'Mozilla/5.0 MyBichordDiagnostic'})
    with urllib.request.urlopen(req, timeout=45) as response:
        return response.read(read_limit)


def pcm(path):
    import numpy as np
    data = subprocess.run(['ffmpeg', '-v', 'error', '-i', str(path), '-t', '100',
                           '-ac', '1', '-ar', '8000', '-f', 'f32le', '-'],
                          capture_output=True, check=True).stdout
    return np.frombuffer(data, dtype='<f4')


def main():
    import numpy as np
    info = json.loads(request('getSong.view', id=os.environ['ND_DIAGNOSTIC_TRACK']))['subsonic-response']['song']
    print('Track duration:', info['duration'])
    original = request('stream.view', id=info['id'], format='raw', read_limit=4)
    print('Original format:', info['suffix'], 'stream signature:', original.hex())
    with tempfile.TemporaryDirectory(prefix='mybichord-seek-') as directory:
        for codec in ('aac', 'mp3'):
            paths = []
            for offset in (0, 60):
                path = pathlib.Path(directory) / f'{codec}-{offset}.{codec}'
                path.write_bytes(request('stream.view', id=info['id'], format=codec, maxBitRate=192, timeOffset=offset))
                paths.append(path)
            whole, offset = map(pcm, paths)
            if codec == 'aac':
                packet_data = subprocess.run(['ffprobe', '-v', 'error', '-show_packets',
                                              '-show_entries', 'packet=pts_time,pos,size,duration_time',
                                              '-of', 'json', str(paths[0])], capture_output=True, check=True)
                packets = json.loads(packet_data.stdout)['packets']
                average = int(sum(int(p['size']) for p in packets[:1000]) / 1000)
                frame_duration = float(packets[0]['duration_time'])
                byte_target = int(packets[0]['pos']) + int(60 / frame_duration) * average
                actual = next(p for p in packets if int(p['pos']) >= byte_target)
                print('AAC cached CBR estimate: requested 60 seconds; audio at',
                      actual['pts_time'], 'seconds')
            needle = offset[8000:8000 * 6]
            fft_size = 1 << (len(whole) + len(needle) - 1).bit_length()
            convolution = np.fft.irfft(np.fft.rfft(whole, fft_size) *
                                       np.fft.rfft(needle[::-1], fft_size), fft_size)
            matches = convolution[len(needle) - 1:len(whole)]
            best = int(np.argmax(matches))
            segment = whole[best:best + len(needle)]
            similarity = float(np.dot(segment, needle) / (np.linalg.norm(segment) * np.linalg.norm(needle)))
            print(codec, 'actual response start seconds:', round(best / 8000 - 1, 3),
                  'correlation:', round(similarity, 4))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Diagnostic failed:', type(error).__name__, getattr(error, 'code', ''))
        raise SystemExit(1)
