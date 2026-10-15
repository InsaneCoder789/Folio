"""Seekable bounded HTTP reader for selecting entries from a large public ZIP."""
import io
import http.client
import time
import urllib.error
import urllib.request
from collections import OrderedDict
from pathlib import Path
import hashlib
import os
import tempfile
import threading


class RequestBudget:
    """Conservative shared transfer allowance for independent parallel readers."""
    def __init__(self,maximum):self.maximum=maximum;self.reserved=0;self.lock=threading.Lock()
    def reserve(self,count):
        with self.lock:
            if count<0 or self.reserved+count>self.maximum:raise RuntimeError('Shared range-request byte budget exceeded')
            self.reserved+=count


class HttpRangeFile(io.RawIOBase):
    def __init__(self,url,size,block_size=65536,cache_blocks=16,cache_directory=None,max_download_bytes=512*1024*1024,request_budget=None):
        self.url=url;self.size=size;self.position=0
        self.block_size=block_size;self.cache_blocks=cache_blocks
        self.cache=OrderedDict();self.bytes_downloaded=0
        self.max_download_bytes=max_download_bytes
        self.request_budget=request_budget
        self.directory=Path(cache_directory)/hashlib.sha256(url.encode()).hexdigest()[:16] if cache_directory else None
        if self.directory:self.directory.mkdir(parents=True,exist_ok=True)

    def readable(self):return True
    def seekable(self):return True
    def tell(self):return self.position
    def seek(self,offset,whence=io.SEEK_SET):
        position=offset if whence==io.SEEK_SET else self.position+offset if whence==io.SEEK_CUR else self.size+offset if whence==io.SEEK_END else -1
        if position<0:raise ValueError('Invalid archive seek')
        self.position=position;return position

    def _block(self,index):
        if index in self.cache:
            self.cache.move_to_end(index);return self.cache[index]
        start=index*self.block_size;end=min(self.size,start+self.block_size)-1
        cached=self.directory/f'{self.block_size}-{index}.bin' if self.directory else None
        if cached and cached.is_file():
            payload=cached.read_bytes()
            if len(payload)==end-start+1:
                self.cache[index]=payload
                while len(self.cache)>self.cache_blocks:self.cache.popitem(last=False)
                return payload
        request=urllib.request.Request(self.url,headers={'Range':f'bytes={start}-{end}','User-Agent':'Folio-handwriting-research/1.0'})
        for attempt in range(3):
            if self.bytes_downloaded+end-start+1>self.max_download_bytes:raise RuntimeError('Selective archive download exceeded its configured byte budget')
            if self.request_budget:self.request_budget.reserve(end-start+2)
            try:
                with urllib.request.urlopen(request,timeout=30) as response:
                    expected=f'bytes {start}-{end}/{self.size}'
                    if response.status!=206 or response.headers.get('Content-Range')!=expected:
                        raise RuntimeError('Source did not honor the exact byte range; refusing a whole-archive download')
                    try:payload=response.read(end-start+2)
                    except http.client.IncompleteRead as failure:payload=failure.partial
                self.bytes_downloaded+=len(payload)
                if len(payload)==end-start+1:break
                failure=RuntimeError('Incomplete archive range')
            except urllib.error.HTTPError as error:
                if error.code not in (408,429,500,502,503,504):raise
                failure=error
            except (urllib.error.URLError,TimeoutError,OSError) as error:failure=error
            if attempt==2:raise RuntimeError('Archive byte range failed after three bounded attempts') from failure
            time.sleep(.5*(attempt+1))
        self.cache[index]=payload
        if cached:
            fd,name=tempfile.mkstemp(prefix=cached.name+'-',suffix='.partial',dir=cached.parent)
            temporary=Path(name)
            try:
                with os.fdopen(fd,'wb') as stream:stream.write(payload)
                temporary.replace(cached)
            finally:temporary.unlink(missing_ok=True)
        while len(self.cache)>self.cache_blocks:self.cache.popitem(last=False)
        return payload

    def read(self,size=-1):
        count=min(self.size-self.position,size if size>=0 else self.size-self.position)
        if count>16*1024*1024:raise ValueError('An archive read exceeds the 16 MB safety bound')
        if count<=0:return b''
        chunks=[]
        while count:
            index,offset=divmod(self.position,self.block_size)
            block=self._block(index);chunk=block[offset:offset+count]
            if not chunk:raise EOFError('Unexpected archive boundary')
            chunks.append(chunk);self.position+=len(chunk);count-=len(chunk)
        return b''.join(chunks)


class ZipSegment(io.RawIOBase):
    """Expose a stored ZIP member as its own seekable archive without extracting it."""
    def __init__(self,parent,offset,size):
        self.parent=parent;self.offset=offset;self.size=size;self.position=0
    def readable(self):return True
    def seekable(self):return True
    def tell(self):return self.position
    def seek(self,offset,whence=io.SEEK_SET):
        position=offset if whence==io.SEEK_SET else self.position+offset if whence==io.SEEK_CUR else self.size+offset if whence==io.SEEK_END else -1
        if position<0:raise ValueError('Invalid nested archive seek')
        self.position=position;return position
    def read(self,size=-1):
        count=min(self.size-self.position,size if size>=0 else self.size-self.position)
        if count<=0:return b''
        self.parent.seek(self.offset+self.position)
        payload=self.parent.read(count);self.position+=len(payload);return payload


def stored_member(archive,info):
    import struct,zipfile
    if info.compress_type!=zipfile.ZIP_STORED:raise ValueError('Nested archive must be stored without outer compression')
    parent=archive.fp;parent.seek(info.header_offset)
    header=parent.read(30)
    if len(header)!=30 or header[:4]!=b'PK\x03\x04':raise ValueError('Invalid ZIP member header')
    name_size,extra_size=struct.unpack_from('<HH',header,26)
    return ZipSegment(parent,info.header_offset+30+name_size+extra_size,info.file_size)
