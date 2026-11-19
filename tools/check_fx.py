import ctypes as C, re, json, itertools
from pathlib import Path
s=(Path(__file__).resolve().parents[1] / 'app/src/main/java/com/jth/smm/GLRenderer.java').read_text()
shaders={}
for m in re.finditer(r'static final String (\w+)\s*=\s*(.*?);(?=\s*(?:static final String|\}))',s,re.S):
    shaders[m[1]]=''.join(shaders[t] if t=='FX_COLOR' else json.loads(t) for t in re.findall(r'"(?:\\.|[^"\\])*"|FX_COLOR',m[2]))
assert all(n in shaders for n in ['VERT','FRAG_EXT','FRAG_2D','FRAG_YUV'])
e=C.CDLL('libEGL.so.1'); I=C.c_int; U=C.c_uint; P=C.c_void_p; F=C.c_float

def egl(n,restype,args):
    f=getattr(e,n);f.restype=restype;f.argtypes=args;return f
getproc=egl('eglGetProcAddress',P,[C.c_char_p])
platform=C.CFUNCTYPE(P,U,P,C.POINTER(I))(getproc(b'eglGetPlatformDisplayEXT'))
d=platform(0x31DD,None,None)
major=I();minor=I();assert egl('eglInitialize',U,[P,C.POINTER(I),C.POINTER(I)])(d,C.byref(major),C.byref(minor)), 'EGL init failed'
assert egl('eglBindAPI',U,[U])(0x30A0)
attrs=(I*13)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
config=P();count=I();assert egl('eglChooseConfig',U,[P,C.POINTER(I),C.POINTER(P),I,C.POINTER(I)])(d,attrs,C.byref(config),1,C.byref(count)) and count.value
surface=egl('eglCreatePbufferSurface',P,[P,P,C.POINTER(I)])(d,config,(I*5)(0x3057,2,0x3056,2,0x3038))
context=egl('eglCreateContext',P,[P,P,P,C.POINTER(I)])(d,config,None,(I*3)(0x3098,2,0x3038))
assert egl('eglMakeCurrent',U,[P,P,P,P])(d,surface,surface,context), 'EGL context failed'

def gl(n,restype,args):
    addr=getproc(n.encode());assert addr,n
    return C.CFUNCTYPE(restype,*args)(addr)
create=gl('glCreateShader',U,[U]);source=gl('glShaderSource',None,[U,I,C.POINTER(C.c_char_p),C.POINTER(I)]);compile=gl('glCompileShader',None,[U]);status=gl('glGetShaderiv',None,[U,U,C.POINTER(I)]);log=gl('glGetShaderInfoLog',None,[U,I,C.POINTER(I),C.c_char_p])

def shader(kind,src):
    obj=create(kind);data=C.c_char_p(src.encode());source(obj,1,C.byref(data),None);compile(obj);ok=I();status(obj,0x8B81,C.byref(ok))
    if not ok.value:
        text=C.create_string_buffer(8192);log(obj,8192,None,text);raise AssertionError(text.value)
    return obj
vs=shader(0x8B31,shaders['VERT']);programs={}
for name in ['FRAG_EXT','FRAG_2D','FRAG_YUV']:
    fs=shader(0x8B30,shaders[name]);p=gl('glCreateProgram',U,[])();attach=gl('glAttachShader',None,[U,U]);attach(p,vs);attach(p,fs);gl('glLinkProgram',None,[U])(p);ok=I();gl('glGetProgramiv',None,[U,U,C.POINTER(I)])(p,0x8B82,C.byref(ok));assert ok.value,name;programs[name]=p
print('External camera, 2D history, and YUV shaders compile and link in OpenGL ES')
p=programs['FRAG_2D'];gl('glUseProgram',None,[U])(p)
uniform=gl('glGetUniformLocation',I,[U,C.c_char_p]);setfloat=gl('glUniform1f',None,[I,F])
identity=(F*16)(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1)
gl('glUniformMatrix4fv',None,[I,I,U,C.POINTER(F)])(uniform(p,b'uTexMatrix'),1,0,identity)
setfloat(uniform(p,b'uGain'),1)
vertices=(F*16)(-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1)
attribute=gl('glGetAttribLocation',I,[U,C.c_char_p]);pointer=gl('glVertexAttribPointer',None,[U,I,U,U,I,P]);enable=gl('glEnableVertexAttribArray',None,[U])
for name,offset in [(b'aPosition',0),(b'aTexCoord',8)]:
    a=attribute(p,name);pointer(a,2,0x1406,0,16,C.addressof(vertices)+offset);enable(a)
tex=U();gl('glGenTextures',None,[I,C.POINTER(U)])(1,C.byref(tex));gl('glActiveTexture',None,[U])(0x84C0);gl('glBindTexture',None,[U,U])(0x0DE1,tex.value)
param=gl('glTexParameteri',None,[U,U,I])
for key,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:param(0x0DE1,key,value)
pixels=(C.c_ubyte*16)(180,100,50,255,20,80,160,255,70,190,100,255,210,50,130,255)
gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0x0DE1,0,0x1908,2,2,0,0x1908,0x1401,pixels)
gl('glUniform1i',None,[I,I])(uniform(p,b'sTexture'),0)
gl('glViewport',None,[I,I,I,I])(0,0,2,2);gl('glDisable',None,[U])(0x0BD0)
setmirror=gl('glUniform2f',None,[I,F,F])
read=gl('glReadPixels',None,[I,I,I,I,U,U,P])
# Identity, producer Y inversion, and a producer 90-degree turn. Asymmetric
# corners distinguish a reflection from a 180-degree rotation or identity.
matrices=[identity,
          (F*16)(1,0,0,0,0,-1,0,0,0,0,1,0,0,1,0,1),
          (F*16)(0,1,0,0,-1,0,0,0,0,0,1,0,1,0,0,1)]
for matrix in matrices:
    gl('glUniformMatrix4fv',None,[I,I,U,C.POINTER(F)])(uniform(p,b'uTexMatrix'),1,0,matrix)
    results={}
    for pop,bw,mirror in itertools.product([0,1],repeat=3):
        for name,value in [(b'uPop',pop),(b'uBw',bw)]:setfloat(uniform(p,name),value)
        for portrait in [False,True]:
            setmirror(uniform(p,b'uMirror'),mirror and not portrait,mirror and portrait)
            gl('glDrawArrays',None,[U,I,I])(5,0,4)
            out=(C.c_ubyte*16)();read(0,0,2,2,0x1908,0x1401,out)
            results[pop,bw,mirror,portrait]=[tuple(out[i:i+4]) for i in range(0,16,4)]
    for pop,bw in itertools.product([0,1],repeat=2):
        original=results[pop,bw,0,False]
        assert results[pop,bw,1,False]==[original[i] for i in [1,0,3,2]], 'Landscape mirror failed'
        # In portrait the GUI's horizontal direction is the fixed video
        # window's Y axis; reflect that axis without changing the other one.
        assert results[pop,bw,1,True]==[original[i] for i in [2,3,0,1]], 'Portrait mirror failed'
        if bw:
            for pixel in original:assert max(pixel[:3])-min(pixel[:3])<=1
    assert results[1,0,0,False]!=results[0,0,0,False]
    for n in [b'uPop',b'uBw']:setfloat(uniform(p,n),0)
    setmirror(uniform(p,b'uMirror'),0,0)
    gl('glDrawArrays',None,[U,I,I])(5,0,4)
    out=(C.c_ubyte*16)();read(0,0,2,2,0x1908,0x1401,out)
    assert [tuple(out[i:i+4]) for i in range(0,16,4)]==results[0,0,0,False]
print('All FX combinations passed in portrait/landscape with identity, inverted, and rotated producer transforms')
