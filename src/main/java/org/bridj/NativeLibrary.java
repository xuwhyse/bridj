/*
 * BridJ - Dynamic and blazing-fast native interop for Java.
 * http://bridj.googlecode.com/
 *
 * Copyright (c) 2010-2015, Olivier Chafik (http://ochafik.com/)
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Olivier Chafik nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY OLIVIER CHAFIK AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
/**
 *
 */
package org.bridj;

import static org.bridj.Pointer.pointerToAddress;
import static org.bridj.util.AnnotationUtils.getAnnotation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bridj.ann.Name;
import org.bridj.demangling.Demangler;
import org.bridj.demangling.Demangler.DemanglingException;
import org.bridj.demangling.Demangler.MemberRef;
import org.bridj.demangling.Demangler.Symbol;
import org.bridj.demangling.GCC4Demangler;
import org.bridj.demangling.VC9Demangler;
import org.bridj.whyse.LogUtil;

/**
 * Representation of a native shared library, with symbols retrieval / matching
 * facilities.<br>
 * This class is not meant to be used by end users, it's used by pluggable
 * runtimes instead.
 *
 * @author ochafik
 */
public class NativeLibrary {

    volatile long handle, symbols;
    String path;
    final File canonicalFile;
    //Map<Class<?>, long[]> callbacks = new HashMap<Class<?>, long[]>();
    NativeEntities nativeEntities = new NativeEntities();
    Map<Long, Symbol> addrToName;
    Map<String, Symbol> nameToSym;
//	Map<String, Long> nameToAddr;

    protected NativeLibrary(String path, long handle, long symbols) throws IOException {
        this.path = path;
        this.handle = handle;
        this.symbols = symbols;
        this.canonicalFile = path == null ? null : new File(path).getCanonicalFile();

        Platform.addNativeLibrary(this);
    }

    long getSymbolsHandle() {
        return symbols;
    }

    NativeEntities getNativeEntities() {
        return nativeEntities;
    }

    static String followGNULDScript(String path) {
        try {
        	  BufferedReader r = new BufferedReader(new FileReader(path));
            try {
                char c;
                while ((c = (char) r.read()) == ' ' || c == '\t' || c == '\n') {
                }
                if (c == '/' && r.read() == '*') {
                    String line;
                    StringBuilder b = new StringBuilder("/*");
                    while ((line = r.readLine()) != null) {
                        b.append(line).append('\n');
                    }
                    String src = b.toString();
                    Pattern ldGroupPattern = Pattern.compile("GROUP\\s*\\(\\s*([^\\s)]+)[^)]*\\)");
                    Matcher m = ldGroupPattern.matcher(src);
                    if (m.find()) {
                        String actualPath = m.group(1);
                        if (BridJ.verbose) {
                            BridJ.info("Parsed LD script '" + path + "', found absolute reference to '" + actualPath + "'");
                        }
                        return actualPath;
                    } else {
                        BridJ.error("Failed to parse LD script '" + path + "' !");
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable th) {
            BridJ.error("Unexpected error: " + th, th);
        }
        return path;
    }

    @SuppressWarnings("deprecation")
    public static NativeLibrary load(String path) throws IOException {
        long handle = 0;
        File file = new File(path);
        boolean exists = file.exists();
        if (file.isAbsolute() && !exists) {
            return null;
        }

        if (Platform.isUnix() && exists) {
            path = followGNULDScript(path);
        }

        handle = JNI.loadLibrary(path);
        if (handle == 0) {
            return null;
        }
        long symbols = JNI.loadLibrarySymbols(path);
        return new NativeLibrary(path, handle, symbols);
    }

    /*public boolean methodMatchesSymbol(Class<?> declaringClass, Method method, String symbol) {
     return symbol.contains(method.getName()) && symbol.contains(declaringClass.getSimpleName());
     }*/
    long getHandle() {
        if (path != null && handle == 0) {
            throw new RuntimeException("Library was released and cannot be used anymore");
        }
        return handle;
    }

    @Override
    protected void finalize() throws Throwable {
        release();
    }

    @SuppressWarnings("deprecation")
    public synchronized void release() {
        if (handle == 0) {
            return;
        }

        if (BridJ.verbose) {
            BridJ.info("Releasing library '" + path + "'");
        }

        nativeEntities.release();

        JNI.freeLibrarySymbols(symbols);
        JNI.freeLibrary(handle);
        handle = 0;

        if (canonicalFile != null && Platform.temporaryExtractedLibraryCanonicalFiles.remove(canonicalFile)) {
            if (canonicalFile.delete()) {
                if (BridJ.verbose) {
                    BridJ.info("Deleted temporary library file '" + canonicalFile + "'");
                }
            } else {
                BridJ.error("Failed to delete temporary library file '" + canonicalFile + "'");
            }
        }

    }

    @SuppressWarnings("deprecation")
    public Pointer<?> getSymbolPointer(String name) {
        return pointerToAddress(getSymbolAddress(name));
    }

    @SuppressWarnings("deprecation")
    public long getSymbolAddress(String name) {
        if (nameToSym != null) {
            Symbol addr = nameToSym.get(name);
//			long addr = nameToAddr.get(name);
//			if (addr != 0)
            if (addr != null) {
                return addr.getAddress();
            }
        }
        long address = JNI.findSymbolInLibrary(getHandle(), name);
        if (address == 0) {
            address = JNI.findSymbolInLibrary(getHandle(), "_" + name);
        }
        return address;
    }

    public synchronized Symbol getSymbol(AnnotatedElement member) throws FileNotFoundException {
        org.bridj.ann.Symbol mg = getAnnotation(org.bridj.ann.Symbol.class, member);
        String name = null;

        Name nameAnn = member.getAnnotation(Name.class);
        if (nameAnn != null) {
            name = nameAnn.value();
        } else if (member instanceof Member) {
            name = ((Member) member).getName();
        }

        List<String> names = new ArrayList<String>();
        if (mg != null) {
            names.addAll(Arrays.asList(mg.value()));
        }
        if (name != null) {
            names.add(name);
        }

        for (String n : names) {
            Symbol handle = getSymbol(n);
            if (handle == null) {
                handle = getSymbol("_" + n);
            }
            if (handle == null) {
                handle = getSymbol(n + (Platform.useUnicodeVersionOfWindowsAPIs ? "W" : "A"));
            }
            if (handle != null) {
                return handle;
            }
        }

        if (member instanceof Method) {
            Method method = (Method) member;
            for (Demangler.Symbol symbol : getSymbols()) {
                if (symbol.matches(method)) {
                	LogUtil.getLog().debug("匹配一个方法!!!!!! : "+symbol.toString());
                    return symbol;
                }
            }
        }
        return null;
    }

    public boolean isMSVC() {
        return Platform.isWindows();
    }

    /**
     * Filter for symbols
     */
    public interface SymbolAccepter {

        boolean accept(Symbol symbol);
    }

    public Symbol getFirstMatchingSymbol(SymbolAccepter accepter) {
        for (Symbol symbol : getSymbols()) {
            if (accepter.accept(symbol)) {
                return symbol;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Collection<Demangler.Symbol> getSymbols() {
        try {
            scanSymbols();
        } catch (Exception ex) {
            assert BridJ.error("Failed to scan symbols of library '" + path + "'", ex);
        }
        return nameToSym == null ? Collections.EMPTY_LIST : Collections.unmodifiableCollection(nameToSym.values());
    }

    @SuppressWarnings("deprecation")
    public String getSymbolName(long address) {
        if (addrToName == null && getSymbolsHandle() != 0)//Platform.isUnix())
        {
            return JNI.findSymbolName(getHandle(), getSymbolsHandle(), address);
        }

        Demangler.Symbol symbol = getSymbol(address);
        return symbol == null ? null : symbol.getSymbol();
    }

    public Symbol getSymbol(long address) {
        try {
            scanSymbols();
            Symbol symbol = addrToName.get(address);
            return symbol;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to get name of address " + address, ex);
        }
    }

    @SuppressWarnings("deprecation")
    public Symbol getSymbol(String name) {
        try {
            Symbol symbol;
            long addr;

            if (nameToSym == null) {// symbols not scanned yet, try without them !
                addr = JNI.findSymbolInLibrary(getHandle(), name);
                if (addr != 0) {
                    symbol = new Symbol(name, this);
                    symbol.setAddress(addr);
                    return symbol;
                }
            }
            scanSymbols();
            if (nameToSym == null) {
                return null;
            }

            symbol = nameToSym.get(name);
            if (addrToName == null) {
                if (symbol == null) {
                    addr = JNI.findSymbolInLibrary(getHandle(), name);
                    if (addr != 0) {
                        symbol = new Symbol(name, this);
                        symbol.setAddress(addr);
                        nameToSym.put(name, symbol);
                    }
                }
            }
            return symbol;
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
//			throw new RuntimeException("Failed to get symbol " + name, ex);
        }
    }

    @SuppressWarnings("deprecation")
    void scanSymbols() throws Exception {
         if (addrToName != null) {
            return;
        }

        nameToSym = new HashMap<String, Symbol>();
//		nameToAddr = new HashMap<String, Long>();

        String[] symbs = null;
        if (symbs == null) {
            //System.out.println("Calling getLibrarySymbols");
            symbs = JNI.getLibrarySymbols(getHandle(), getSymbolsHandle());
            LogUtil.getLog().debug("不可变方法读取dll文件，获得方法如下,需要解析:");
            for(String name : symbs){
            	LogUtil.getLog().debug(name);
            }
            LogUtil.getLog().debug("==================================================");
//            System.out.println("Got " + symbs + " (" + (symbs == null ? "null" : symbs.length + "") + ")");
        }

        if (symbs == null) {
            return;
        }

        addrToName = new HashMap<Long, Demangler.Symbol>();

        //boolean is32 = !Platform.is64Bits();
        for (String name : symbs) {
            if (name == null) {
                continue;
            }

            long addr = JNI.findSymbolInLibrary(getHandle(), name);
            if (addr == 0 && name.startsWith("_")) {
                String n2 = name.substring(1);
                addr = JNI.findSymbolInLibrary(getHandle(), n2);
                if (addr == 0) {
                    n2 = "_" + name;
                    addr = JNI.findSymbolInLibrary(getHandle(), n2);
                }
                if (addr != 0) {
                    name = n2;
                }

            }
            if (addr == 0) {
                if (BridJ.verbose) {
                    BridJ.warning("Symbol '" + name + "' not found.");
                }
                continue;
            }
            //if (is32)
            //	addr = addr & 0xffffffffL;
            //System.out.println("Symbol " + Long.toHexString(addr) + " = '" + name + "'");

            Symbol sym = new Demangler.Symbol(name, this);
            sym.setAddress(addr);
            addrToName.put(addr, sym);
            nameToSym.put(name, sym);
            //nameToAddr.put(name, addr);
            //System.out.println("'" + name + "' = \t" + TestCPP.hex(addr) + "\n\t" + sym.getParsedRef());
        }
        if (BridJ.debug) {
            BridJ.info("Found " + nameToSym.size() + " symbols in '" + path + "' :");

            for (Symbol sym : nameToSym.values()) {
                BridJ.info("DEBUG(BridJ): library=\"" + path + "\", symbol=\"" + sym.getSymbol() + "\", address=" + Long.toHexString(sym.getAddress()) + ", demangled=\"" + sym.getParsedRef() + "\"");
            }

            //for (Symbol sym : nameToSym.values())
            //	System.out.println("Symbol '" + sym + "' = " + sym.getParsedRef());
        }
    }

    public MemberRef parseSymbol(String symbol) throws DemanglingException {
        if ("__cxa_pure_virtual".equals(symbol)) {
            return null;
        }

        if (Platform.isWindows()) {
            try {
                MemberRef result = new VC9Demangler(this, symbol).parseSymbol();
                //============根据所有打印信息，临时修改代码，让它匹配到方法=================
                if(result.paramTypes.length==3){
                	if(result.paramTypes[2].toString().equals("CThostFtdcMdApi*"))
                		result.paramTypes[2] = result.paramTypes[1];
                }
                //==============================================
                LogUtil.getLog().debug("尝试匹配:"+symbol.toString()+"--->"+result.toString());
                LogUtil.getLog().debug("尝试匹配:"+symbol.toString()+"--->"+result.toString());
                if (result != null) {
                    return result;
                }
            } catch (Throwable th) {
            }
        }
        return new GCC4Demangler(this, symbol).parseSymbol();
    }
}
