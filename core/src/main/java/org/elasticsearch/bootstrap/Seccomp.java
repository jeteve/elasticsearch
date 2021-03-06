/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import org.apache.lucene.util.Constants;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 
 * Installs a limited form of secure computing mode,
 * to filters system calls to block process execution.
 * <p>
 * This is only supported on the Linux and Mac OS X operating systems.
 * <p>
 * On Linux it currently supports on the amd64 architecture, on Linux kernels 3.5 or above, and requires
 * {@code CONFIG_SECCOMP} and {@code CONFIG_SECCOMP_FILTER} compiled into the kernel.
 * <p>
 * On Linux BPF Filters are installed using either {@code seccomp(2)} (3.17+) or {@code prctl(2)} (3.5+). {@code seccomp(2)}
 * is preferred, as it allows filters to be applied to any existing threads in the process, and one motivation
 * here is to protect against bugs in the JVM. Otherwise, code will fall back to the {@code prctl(2)} method 
 * which will at least protect elasticsearch application threads.
 * <p>
 * Linux BPF filters will return {@code EACCES} (Access Denied) for the following system calls:
 * <ul>
 *   <li>{@code execve}</li>
 *   <li>{@code fork}</li>
 *   <li>{@code vfork}</li>
 *   <li>{@code execveat}</li>
 * </ul>
 * <p>
 * On Mac OS X Leopard or above, a custom {@code sandbox(7)} ("Seatbelt") profile is installed that
 * denies the following rules:
 * <ul>
 *   <li>{@code process-fork}</li>
 *   <li>{@code process-exec}</li>
 * </ul>
 * <p>
 * This is not intended as a sandbox. It is another level of security, mostly intended to annoy
 * security researchers and make their lives more difficult in achieving "remote execution" exploits.
 * @see <a href="http://www.kernel.org/doc/Documentation/prctl/seccomp_filter.txt">
 *      http://www.kernel.org/doc/Documentation/prctl/seccomp_filter.txt</a>
 * @see <a href="https://reverse.put.as/wp-content/uploads/2011/06/The-Apple-Sandbox-BHDC2011-Paper.pdf">
 *      https://reverse.put.as/wp-content/uploads/2011/06/The-Apple-Sandbox-BHDC2011-Paper.pdf</a>
 */
// not an example of how to write code!!!
final class Seccomp {
    private static final ESLogger logger = Loggers.getLogger(Seccomp.class);

    // Linux implementation, based on seccomp(2) or prctl(2) with bpf filtering

    /** Access to non-standard Linux libc methods */
    static interface LinuxLibrary extends Library {
        /** 
         * maps to prctl(2) 
         */
        int prctl(int option, long arg2, long arg3, long arg4, long arg5);
        /** 
         * used to call seccomp(2), its too new... 
         * this is the only way, DONT use it on some other architecture unless you know wtf you are doing 
         */
        long syscall(long number, Object... args);
    };

    // null if unavailable or something goes wrong.
    static final LinuxLibrary linux_libc;

    static {
        LinuxLibrary lib = null;
        if (Constants.LINUX) {
            try {
                lib = (LinuxLibrary) Native.loadLibrary("c", LinuxLibrary.class);
            } catch (UnsatisfiedLinkError e) {
                logger.warn("unable to link C library. native methods (seccomp) will be disabled.", e);
            }
        }
        linux_libc = lib;
    }
    
    /** the preferred method is seccomp(2), since we can apply to all threads of the process */
    static final int SECCOMP_SYSCALL_NR        = 317;   // since Linux 3.17
    static final int SECCOMP_SET_MODE_FILTER   =   1;   // since Linux 3.17
    static final int SECCOMP_FILTER_FLAG_TSYNC =   1;   // since Linux 3.17

    /** otherwise, we can use prctl(2), which will at least protect ES application threads */
    static final int PR_GET_NO_NEW_PRIVS       =  39;   // since Linux 3.5
    static final int PR_SET_NO_NEW_PRIVS       =  38;   // since Linux 3.5
    static final int PR_GET_SECCOMP            =  21;   // since Linux 2.6.23
    static final int PR_SET_SECCOMP            =  22;   // since Linux 2.6.23
    static final int SECCOMP_MODE_FILTER       =   2;   // since Linux Linux 3.5
    
    /** corresponds to struct sock_filter */
    static final class SockFilter {
        short code; // insn
        byte jt;    // number of insn to jump (skip) if true
        byte jf;    // number of insn to jump (skip) if false
        int k;      // additional data

        SockFilter(short code, byte jt, byte jf, int k) {
            this.code = code;
            this.jt = jt;
            this.jf = jf;
            this.k = k;
        }
    }
    
    /** corresponds to struct sock_fprog */
    public static final class SockFProg extends Structure implements Structure.ByReference {
        public short   len;           // number of filters
        public Pointer filter;        // filters
        
        public SockFProg(SockFilter filters[]) {
            len = (short) filters.length;
            // serialize struct sock_filter * explicitly, its less confusing than the JNA magic we would need
            Memory filter = new Memory(len * 8);
            ByteBuffer bbuf = filter.getByteBuffer(0, len * 8);
            bbuf.order(ByteOrder.nativeOrder()); // little endian
            for (SockFilter f : filters) {
                bbuf.putShort(f.code);
                bbuf.put(f.jt);
                bbuf.put(f.jf);
                bbuf.putInt(f.k);
            }
            this.filter = filter;
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(new String[] { "len", "filter" });
        }
    }
    
    // BPF "macros" and constants
    static final int BPF_LD  = 0x00;
    static final int BPF_W   = 0x00;
    static final int BPF_ABS = 0x20;
    static final int BPF_JMP = 0x05;
    static final int BPF_JEQ = 0x10;
    static final int BPF_JGE = 0x30;
    static final int BPF_JGT = 0x20;
    static final int BPF_RET = 0x06;
    static final int BPF_K   = 0x00;
    
    static SockFilter BPF_STMT(int code, int k) {
        return new SockFilter((short) code, (byte) 0, (byte) 0, k);
    }
    
    static SockFilter BPF_JUMP(int code, int k, int jt, int jf) {
        return new SockFilter((short) code, (byte) jt, (byte) jf, k);
    }
    
    static final int AUDIT_ARCH_X86_64 = 0xC000003E;
    static final int SECCOMP_RET_ERRNO = 0x00050000;
    static final int SECCOMP_RET_DATA  = 0x0000FFFF;
    static final int SECCOMP_RET_ALLOW = 0x7FFF0000;

    // some errno constants for error checking/handling
    static final int EACCES = 0x0D;
    static final int EFAULT = 0x0E;
    static final int EINVAL = 0x16;
    static final int ENOSYS = 0x26;

    // offsets (arch dependent) that our BPF checks
    static final int SECCOMP_DATA_NR_OFFSET   = 0x00;
    static final int SECCOMP_DATA_ARCH_OFFSET = 0x04;
    
    // currently these ranges are blocked (inclusive):
    // execve is really the only one needed but why let someone fork a 30G heap? (not really what happens)
    // ...
    // 57: fork
    // 58: vfork
    // 59: execve
    // ...
    // 322: execveat
    // ...
    static final int NR_SYSCALL_FORK     = 57;
    static final int NR_SYSCALL_EXECVE   = 59;
    static final int NR_SYSCALL_EXECVEAT = 322;  // since Linux 3.19

    /** try to install our BPF filters via seccomp() or prctl() to block execution */
    private static void linuxImpl() {
        // first be defensive: we can give nice errors this way, at the very least.
        // also, some of these security features get backported to old versions, checking kernel version here is a big no-no! 
        boolean supported = Constants.LINUX && "amd64".equals(Constants.OS_ARCH);
        if (supported == false) {
            throw new UnsupportedOperationException("seccomp unavailable: '" + Constants.OS_ARCH + "' architecture unsupported");
        }
        
        // we couldn't link methods, could be some really ancient kernel (e.g. < 2.1.57) or some bug
        if (linux_libc == null) {
            throw new UnsupportedOperationException("seccomp unavailable: could not link methods. requires kernel 3.5+ with CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER compiled in");
        }

        // check for kernel version
        if (linux_libc.prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) < 0) {
            int errno = Native.getLastError();
            switch (errno) {
                case ENOSYS: throw new UnsupportedOperationException("seccomp unavailable: requires kernel 3.5+ with CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER compiled in");
                default: throw new UnsupportedOperationException("prctl(PR_GET_NO_NEW_PRIVS): " + JNACLibrary.strerror(errno));
            }
        }
        // check for SECCOMP
        if (linux_libc.prctl(PR_GET_SECCOMP, 0, 0, 0, 0) < 0) {
            int errno = Native.getLastError();
            switch (errno) {
                case EINVAL: throw new UnsupportedOperationException("seccomp unavailable: CONFIG_SECCOMP not compiled into kernel, CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER are needed");
                default: throw new UnsupportedOperationException("prctl(PR_GET_SECCOMP): " + JNACLibrary.strerror(errno));
            }
        }
        // check for SECCOMP_MODE_FILTER
        if (linux_libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, 0, 0, 0) < 0) {
            int errno = Native.getLastError();
            switch (errno) {
                case EFAULT: break; // available
                case EINVAL: throw new UnsupportedOperationException("seccomp unavailable: CONFIG_SECCOMP_FILTER not compiled into kernel, CONFIG_SECCOMP and CONFIG_SECCOMP_FILTER are needed");
                default: throw new UnsupportedOperationException("prctl(PR_SET_SECCOMP): " + JNACLibrary.strerror(errno));
            }
        }

        // ok, now set PR_SET_NO_NEW_PRIVS, needed to be able to set a seccomp filter as ordinary user
        if (linux_libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
            throw new UnsupportedOperationException("prctl(PR_SET_NO_NEW_PRIVS): " + JNACLibrary.strerror(Native.getLastError()));
        }
        
        // BPF installed to check arch, then syscall range. See https://www.kernel.org/doc/Documentation/prctl/seccomp_filter.txt for details.
        SockFilter insns[] = {
          /* 1 */ BPF_STMT(BPF_LD  + BPF_W   + BPF_ABS, SECCOMP_DATA_ARCH_OFFSET),               //
          /* 2 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   AUDIT_ARCH_X86_64, 0, 4),                // if (arch != amd64) goto fail;
          /* 3 */ BPF_STMT(BPF_LD  + BPF_W   + BPF_ABS, SECCOMP_DATA_NR_OFFSET),                 //
          /* 4 */ BPF_JUMP(BPF_JMP + BPF_JGE + BPF_K,   NR_SYSCALL_FORK, 0, 3),                  // if (syscall < SYSCALL_FORK) goto pass;
          /* 5 */ BPF_JUMP(BPF_JMP + BPF_JEQ + BPF_K,   NR_SYSCALL_EXECVEAT, 1, 0),              // if (syscall == SYSCALL_EXECVEAT) goto fail;
          /* 6 */ BPF_JUMP(BPF_JMP + BPF_JGT + BPF_K,   NR_SYSCALL_EXECVE, 1, 0),                // if (syscall > SYSCALL_EXECVE) goto pass;
          /* 7 */ BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),    // fail: return EACCES;
          /* 8 */ BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_ALLOW)                                   // pass: return OK;
        };
        
        // seccomp takes a long, so we pass it one explicitly to keep the JNA simple
        SockFProg prog = new SockFProg(insns);
        prog.write();
        long pointer = Pointer.nativeValue(prog.getPointer());

        // install filter, if this works, after this there is no going back!
        // first try it with seccomp(SECCOMP_SET_MODE_FILTER), falling back to prctl()
        if (linux_libc.syscall(SECCOMP_SYSCALL_NR, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, pointer) != 0) {
            int errno1 = Native.getLastError();
            if (logger.isDebugEnabled()) {
                logger.debug("seccomp(SECCOMP_SET_MODE_FILTER): " + JNACLibrary.strerror(errno1) + ", falling back to prctl(PR_SET_SECCOMP)...");
            }
            if (linux_libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, pointer, 0, 0) < 0) {
                int errno2 = Native.getLastError();
                throw new UnsupportedOperationException("seccomp(SECCOMP_SET_MODE_FILTER): " + JNACLibrary.strerror(errno1) + 
                                                        ", prctl(PR_SET_SECCOMP): " + JNACLibrary.strerror(errno2));
            }
        }
        
        // now check that the filter was really installed, we should be in filter mode.
        if (linux_libc.prctl(PR_GET_SECCOMP, 0, 0, 0, 0) != 2) {
            throw new UnsupportedOperationException("seccomp filter installation did not really succeed. seccomp(PR_GET_SECCOMP): " + JNACLibrary.strerror(Native.getLastError()));
        }

        logger.debug("Linux seccomp filter installation successful");
    }

    // OS X implementation via sandbox(7)

    /** Access to non-standard OS X libc methods */
    static interface MacLibrary extends Library {
        /**
         * maps to sandbox_init(3), since Leopard
         */
        int sandbox_init(String profile, long flags, PointerByReference errorbuf);

        /**
         * releases memory when an error occurs during initialization (e.g. syntax bug)
         */
        void sandbox_free_error(Pointer errorbuf);
    }

    // null if unavailable, or something goes wrong.
    static final MacLibrary libc_mac;

    static {
        MacLibrary lib = null;
        if (Constants.MAC_OS_X) {
            try {
                lib = (MacLibrary) Native.loadLibrary("c", MacLibrary.class);
            } catch (UnsatisfiedLinkError e) {
                logger.warn("unable to link C library. native methods (seatbelt) will be disabled.", e);
            }
        }
        libc_mac = lib;
    }

    /** The only supported flag... */
    static final int SANDBOX_NAMED = 1;
    /** Allow everything except process fork and execution */
    static final String SANDBOX_RULES = "(version 1) (allow default) (deny process-fork) (deny process-exec)";

    /** try to install our custom rule profile into sandbox_init() to block execution */
    private static void macImpl(Path tmpFile) throws IOException {
        // first be defensive: we can give nice errors this way, at the very least.
        boolean supported = Constants.MAC_OS_X;
        if (supported == false) {
            throw new IllegalStateException("bug: should not be trying to initialize seccomp for an unsupported OS");
        }

        // we couldn't link methods, could be some really ancient OS X (< Leopard) or some bug
        if (libc_mac == null) {
            throw new UnsupportedOperationException("seatbelt unavailable: could not link methods. requires Leopard or above.");
        }

        // write rules to a temporary file, which will be passed to sandbox_init()
        Path rules = Files.createTempFile(tmpFile, "es", "sb");
        Files.write(rules, Collections.singleton(SANDBOX_RULES));

        boolean success = false;
        try {
            PointerByReference errorRef = new PointerByReference();
            int ret = libc_mac.sandbox_init(rules.toAbsolutePath().toString(), SANDBOX_NAMED, errorRef);
            // if sandbox_init() fails, add the message from the OS (e.g. syntax error) and free the buffer
            if (ret != 0) {
                Pointer errorBuf = errorRef.getValue();
                RuntimeException e = new UnsupportedOperationException("sandbox_init(): " + errorBuf.getString(0));
                libc_mac.sandbox_free_error(errorBuf);
                throw e;
            }
            logger.debug("OS X seatbelt initialization successful");
            success = true;
        } finally {
            if (success) {
                Files.delete(rules);
            } else {
                IOUtils.deleteFilesIgnoringExceptions(rules);
            }
        }
    }

    /**
     * Attempt to drop the capability to execute for the process.
     * <p>
     * This is best effort and OS and architecture dependent. It may throw any Throwable.
     */
    static void init(Path tmpFile) throws Throwable {
        if (Constants.LINUX) {
            linuxImpl();
        } else if (Constants.MAC_OS_X) {
            macImpl(tmpFile);
        } else {
            throw new UnsupportedOperationException("syscall filtering not supported for OS: '" + Constants.OS_NAME + "'");
        }
    }
}
