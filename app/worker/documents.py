"""Bounded local extraction; document content never leaves the container."""
import os
from pathlib import Path
import resource
import subprocess
import tempfile

TEXT_LIMIT = 150_000


def limits():
    resource.setrlimit(resource.RLIMIT_AS, (600 << 20, 600 << 20))
    resource.setrlimit(resource.RLIMIT_CPU, (25,25))
    resource.setrlimit(resource.RLIMIT_FSIZE, (32 << 20,32 << 20))


def command(args, cwd):
    subprocess.run(args, cwd=cwd, timeout=35, check=True, stdin=subprocess.DEVNULL,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, preexec_fn=limits,
                   env={'PATH':os.defpath,'HOME':str(cwd),'OMP_THREAD_LIMIT':'1'})


def extract(name,data):
    with tempfile.TemporaryDirectory() as work:
        root=Path(work)
        (root/'original').write_bytes(data)
        if data.startswith(b'%PDF-'):
            command(['pdftotext','-l','50','-layout','original','text'],root)
            text=(root/'text').read_text(errors='replace')
            if text.strip():
                return text[:TEXT_LIMIT],'Text extracted (first 50 pages)'
            command(['pdftoppm','-f','1','-l','10','-scale-to','1600','-png','original','scan'],root)
            pages=sorted(root.glob('scan-*.png'),key=lambda p:int(p.stem.split('-')[-1]))
        elif data.startswith((b'\x89PNG\r\n\x1a\n',b'\xff\xd8\xff',b'II*\x00',b'MM\x00*')):
            pages=[root/'original']
        elif Path(name).suffix.lower() in ('.txt','.csv','.md','.tsv','.json'):
            return data[:TEXT_LIMIT*4].decode('utf-8-sig',errors='replace')[:TEXT_LIMIT], 'Text extracted'
        else:
            return '', 'Original saved · text extraction unavailable'
        chunks=[]
        for page in pages:
            command(['tesseract',page.name,'out','-l','eng'],root)
            chunks.append((root/'out.txt').read_text(errors='replace'))
        text='\n'.join(chunks).strip()
        return text[:TEXT_LIMIT], 'OCR complete (first 10 pages)' if text else 'Original saved · no readable text'
