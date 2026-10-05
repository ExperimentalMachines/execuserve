# /// script
# requires-python = ">=3.11"
# dependencies = ["segno"]
# ///
# The end card's QR code: the project page. uv run qr.py -> assets/qr.svg
import segno
segno.make('https://experimentalmachines.org/execuserve/', error='m').save(
    __file__.rsplit('/', 1)[0] + '/assets/qr.svg', scale=10, border=0, dark='#262626', light=None)
print('wrote assets/qr.svg')
