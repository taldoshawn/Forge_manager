package com.forgemanager.app.features.resources

import java.nio.charset.StandardCharsets

/** Conservative Android binary string-pool reader/patcher for AXML/resources.arsc. */
object BinaryStringPools {
    data class Entry(val poolOffset:Int,val index:Int,val value:String,val utf8:Boolean,val firstLengthOffset:Int,val firstLengthBytes:Int,val secondLengthOffset:Int,val secondLengthBytes:Int,val dataOffset:Int,val encodedCapacity:Int,val utf16Capacity:Int){val key:String get()="$poolOffset:$index"}
    fun find(data:ByteArray,maxStrings:Int=100_000):List<Entry>{val r=ArrayList<Entry>();var o=0;while(o+28<=data.size&&r.size<maxStrings){if(u16(data,o)==RES_STRING_POOL_TYPE)parsePool(data,o,r,maxStrings);o+=4};return r.distinctBy{it.key}}
    fun replace(data:ByteArray,entry:Entry,replacement:String){if(entry.utf8)replaceUtf8(data,entry,replacement)else replaceUtf16(data,entry,replacement)}
    private fun parsePool(d:ByteArray,b:Int,out:MutableList<Entry>,max:Int){val hs=u16(d,b+2);val cs=u32(d,b+4);if(hs<28||cs<hs||b+cs>d.size)return;val sc=u32(d,b+8);val stc=u32(d,b+12);val flags=u32(d,b+16);val ss=u32(d,b+20);val sts=u32(d,b+24);if(sc<0||sc>1_000_000)return;val os=b+hs;val ob=sc.toLong()*4+stc.toLong()*4;if(os.toLong()+ob>b.toLong()+cs)return;if(ss<hs||ss>=cs)return;if(sts!=0&&(sts<ss||sts>cs))return;val utf8=flags and UTF8_FLAG!=0;for(i in 0 until sc){if(out.size>=max)return;val rel=u32(d,os+i*4);if(rel<0)continue;val start=b+ss+rel;if(start<b||start>=b+cs)continue;runCatching{if(utf8)parseUtf8(d,b,cs,i,start)else parseUtf16(d,b,cs,i,start)}.getOrNull()?.let(out::add)}}
    private fun parseUtf8(d:ByteArray,p:Int,cs:Int,i:Int,s:Int):Entry?{val a=read8(d,s)?:return null;val so=s+a.bytes;val z=read8(d,so)?:return null;val t=so+z.bytes;val end=t+z.value;if(end>=p+cs||end>=d.size)return null;return Entry(p,i,d.copyOfRange(t,end).toString(StandardCharsets.UTF_8),true,s,a.bytes,so,z.bytes,t,z.value,a.value)}
    private fun parseUtf16(d:ByteArray,p:Int,cs:Int,i:Int,s:Int):Entry?{val l=read16(d,s)?:return null;val t=s+l.bytes;val end=t.toLong()+l.value*2L;if(end+2>p.toLong()+cs||end+2>d.size)return null;return Entry(p,i,d.copyOfRange(t,end.toInt()).toString(StandardCharsets.UTF_16LE),false,s,l.bytes,-1,0,t,l.value*2,l.value)}
    private fun replaceUtf8(d:ByteArray,e:Entry,r:String){val enc=r.toByteArray(StandardCharsets.UTF_8);require(enc.size<=e.encodedCapacity){"Substituição UTF-8 excede ${e.encodedCapacity} bytes"};require(r.length<=e.utf16Capacity);write8(d,e.firstLengthOffset,e.firstLengthBytes,r.length);write8(d,e.secondLengthOffset,e.secondLengthBytes,enc.size);enc.copyInto(d,e.dataOffset);for(i in e.dataOffset+enc.size until (e.dataOffset+e.encodedCapacity+1).coerceAtMost(d.size))d[i]=0}
    private fun replaceUtf16(d:ByteArray,e:Entry,r:String){val enc=r.toByteArray(StandardCharsets.UTF_16LE);require(r.length<=e.utf16Capacity);write16(d,e.firstLengthOffset,e.firstLengthBytes,r.length);enc.copyInto(d,e.dataOffset);for(i in e.dataOffset+enc.size until (e.dataOffset+e.encodedCapacity+2).coerceAtMost(d.size))d[i]=0}
    private data class Length(val value:Int,val bytes:Int)
    private fun read8(d:ByteArray,o:Int):Length?{if(o!in d.indices)return null;val a=d[o].toInt()and 255;return if(a and 128==0)Length(a,1)else if(o+1>=d.size)null else Length(((a and 127)shl 8)or(d[o+1].toInt()and 255),2)}
    private fun write8(d:ByteArray,o:Int,b:Int,v:Int){if(b==1){require(v<=127);d[o]=v.toByte()}else{require(b==2&&v<=32767);d[o]=(128 or((v ushr 8)and127)).toByte();d[o+1]=(v and255).toByte()}}
    private fun read16(d:ByteArray,o:Int):Length?{if(o+1>=d.size)return null;val a=u16(d,o);return if(a and 0x8000==0)Length(a,2)else if(o+3>=d.size)null else Length(((a and 0x7fff)shl16)or u16(d,o+2),4)}
    private fun write16(d:ByteArray,o:Int,b:Int,v:Int){if(b==2){require(v<=0x7fff);put16(d,o,v)}else{require(b==4&&v<=0x7fffffff);put16(d,o,0x8000 or((v ushr16)and0x7fff));put16(d,o+2,v and0xffff)}}
    private fun u16(d:ByteArray,o:Int)=if(o<0||o+1>=d.size)-1 else(d[o].toInt()and255)or((d[o+1].toInt()and255)shl8)
    private fun u32(d:ByteArray,o:Int)=if(o<0||o+3>=d.size)-1 else(d[o].toInt()and255)or((d[o+1].toInt()and255)shl8)or((d[o+2].toInt()and255)shl16)or((d[o+3].toInt()and255)shl24)
    private fun put16(d:ByteArray,o:Int,v:Int){d[o]=(v and255).toByte();d[o+1]=((v ushr8)and255).toByte()}
    private const val RES_STRING_POOL_TYPE=1;private const val UTF8_FLAG=0x100
}
