/* JNI bridge: local TUN -> zdtun -> protected sockets. No remote VPN endpoint. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <errno.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include "third_party/zdtun/zdtun.h"
#include "third_party/zstd/lib/zstd.h"

typedef struct {
    int wake[2]; int tun_fd; atomic_int stopped;
    JNIEnv *env; jobject callbacks; jmethodID protect,packet;
    uint64_t packets;
} bridge;

static void exception_clear(JNIEnv *env) { if((*env)->ExceptionCheck(env))(*env)->ExceptionClear(env); }
static void socket_open(zdtun_t *tun,socket_t fd) {
    bridge *ctx=zdtun_userdata(tun);
    jboolean ok=(*ctx->env)->CallBooleanMethod(ctx->env,ctx->callbacks,ctx->protect,(jint)fd);
    if((*ctx->env)->ExceptionCheck(ctx->env)){exception_clear(ctx->env);ok=JNI_FALSE;}
    if(!ok){
        /* Keep the descriptor owned by zdtun, but make it unusable on failure.
           Otherwise a non-protected forward socket could recurse into the VPN. */
        atomic_store(&ctx->stopped,1);
        dup2(ctx->wake[0],fd);
    }
}
static int send_client(zdtun_t *tun,zdtun_pkt_t *packet,const zdtun_conn_t *conn) {
    (void)conn;bridge *ctx=zdtun_userdata(tun);
    if(atomic_load(&ctx->stopped))return -1;
    ssize_t n;
    do { n=write(ctx->tun_fd,packet->buf,packet->len); } while(n<0&&errno==EINTR);
    return n==packet->len?0:-1;
}
static void account(zdtun_t *tun,const zdtun_pkt_t *pkt,uint8_t outgoing,const zdtun_conn_t *conn) {
    bridge *ctx=zdtun_userdata(tun);
    if(pkt->tuple.ipproto!=IPPROTO_UDP||pkt->l7_len<2||pkt->l7_len>65507)return;
    const zdtun_5tuple_t *tuple=zdtun_conn_get_5tuple(conn);
    char peer[INET6_ADDRSTRLEN];
    if(!inet_ntop(tuple->ipver==6?AF_INET6:AF_INET,&tuple->dst_ip,peer,sizeof(peer)))return;
    JNIEnv *env=ctx->env;
    jbyteArray body=(*env)->NewByteArray(env,pkt->l7_len);
    if(!body){exception_clear(env);return;}
    (*env)->SetByteArrayRegion(env,body,0,pkt->l7_len,(const jbyte*)pkt->l7);
    jstring host=(*env)->NewStringUTF(env,peer);
    if(!host){exception_clear(env);(*env)->DeleteLocalRef(env,body);return;}
    struct timespec ts;clock_gettime(CLOCK_REALTIME,&ts);
    jdouble now=(jdouble)ts.tv_sec+ts.tv_nsec/1e9;
    (*env)->CallVoidMethod(env,ctx->callbacks,ctx->packet,(jboolean)outgoing,host,
        (jint)ntohs(tuple->src_port),(jint)ntohs(tuple->dst_port),body,now);
    exception_clear(env);
    (*env)->DeleteLocalRef(env,body);(*env)->DeleteLocalRef(env,host);ctx->packets++;
}
JNIEXPORT jlong JNICALL Java_local_udp_esp_vpn_VpnNative_create(JNIEnv *env,jclass cls) {
    (void)env;(void)cls;bridge *ctx=calloc(1,sizeof(*ctx));if(!ctx)return 0;
    if(pipe(ctx->wake)<0){free(ctx);return 0;}
    fcntl(ctx->wake[0],F_SETFL,O_NONBLOCK);fcntl(ctx->wake[1],F_SETFL,O_NONBLOCK);
    fcntl(ctx->wake[0],F_SETFD,FD_CLOEXEC);fcntl(ctx->wake[1],F_SETFD,FD_CLOEXEC);
    atomic_init(&ctx->stopped,0);return (jlong)(intptr_t)ctx;
}
JNIEXPORT void JNICALL Java_local_udp_esp_vpn_VpnNative_stop(JNIEnv *env,jclass cls,jlong handle) {
    (void)env;(void)cls;bridge *ctx=(bridge*)(intptr_t)handle;if(!ctx)return;
    atomic_store(&ctx->stopped,1);char byte=1;(void)write(ctx->wake[1],&byte,1);
}
JNIEXPORT void JNICALL Java_local_udp_esp_vpn_VpnNative_destroy(JNIEnv *env,jclass cls,jlong handle) {
    (void)env;(void)cls;bridge *ctx=(bridge*)(intptr_t)handle;if(!ctx)return;
    close(ctx->wake[0]);close(ctx->wake[1]);free(ctx);
}
JNIEXPORT jint JNICALL Java_local_udp_esp_vpn_VpnNative_run(JNIEnv *env,jclass cls,jlong handle,jint fd,jobject callbacks) {
    (void)cls;bridge *ctx=(bridge*)(intptr_t)handle;
    if(!ctx||fd<0||fd>=FD_SETSIZE||ctx->wake[0]>=FD_SETSIZE)return -1;
    ctx->env=env;ctx->callbacks=callbacks;ctx->tun_fd=fd;
    jclass type=(*env)->GetObjectClass(env,callbacks);
    ctx->protect=(*env)->GetMethodID(env,type,"protectForwardSocket","(I)Z");
    ctx->packet=(*env)->GetMethodID(env,type,"onUdp","(ZLjava/lang/String;II[BD)V");
    (*env)->DeleteLocalRef(env,type);
    if(!ctx->protect||!ctx->packet||(*env)->ExceptionCheck(env)){exception_clear(env);return -2;}
    zdtun_callbacks_t cb={0};cb.send_client=send_client;cb.account_packet=account;cb.on_socket_open=socket_open;
    zdtun_t *tun=zdtun_init(&cb,ctx);if(!tun)return -3;
    zdtun_set_mtu(tun,1500);fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK);
    char *buffer=malloc(65535);if(!buffer){zdtun_finalize(tun);return -4;}
    time_t last_purge=0;int result=0;
    while(!atomic_load(&ctx->stopped)) {
        fd_set rd,wr;int maxfd;zdtun_fds(tun,&maxfd,&rd,&wr);
        FD_SET(fd,&rd);FD_SET(ctx->wake[0],&rd);
        if(fd>maxfd)maxfd=fd;if(ctx->wake[0]>maxfd)maxfd=ctx->wake[0];
        struct timeval timeout={.tv_sec=0,.tv_usec=250000};
        int ready=select(maxfd+1,&rd,&wr,NULL,&timeout);
        if(ready<0){if(errno==EINTR)continue;result=-5;break;}
        if(atomic_load(&ctx->stopped)||FD_ISSET(ctx->wake[0],&rd))break;
        if(ready>0){
            if(FD_ISSET(fd,&rd)) {
                /* Bound each drain to keep reverse traffic and Stop responsive. */
                for(int i=0;i<64&&!atomic_load(&ctx->stopped);i++){
                    ssize_t n=read(fd,buffer,65535);
                    if(n<0){if(errno==EINTR){i--;continue;}if(errno==EAGAIN||errno==EWOULDBLOCK)break;result=-6;atomic_store(&ctx->stopped,1);break;}
                    if(n==0){result=-7;atomic_store(&ctx->stopped,1);break;}
                    zdtun_easy_forward(tun,buffer,(int)n);
                }
            }
            FD_CLR(fd,&rd);FD_CLR(ctx->wake[0],&rd);
            zdtun_handle_fd(tun,&rd,&wr);
        }
        time_t now=time(NULL);if(now!=last_purge){zdtun_purge_expired(tun);last_purge=now;}
    }
    free(buffer);zdtun_finalize(tun);return result;
}
JNIEXPORT jbyteArray JNICALL Java_local_udp_esp_vpn_VpnNative_decompress(JNIEnv *env,jclass cls,jbyteArray compressed) {
    (void)cls;jsize input_size=(*env)->GetArrayLength(env,compressed);
    jbyte *input=(*env)->GetByteArrayElements(env,compressed,NULL);if(!input)return NULL;
    unsigned long long size=ZSTD_getFrameContentSize(input,input_size);
    if(size==0||size>8*1024*1024){(*env)->ReleaseByteArrayElements(env,compressed,input,JNI_ABORT);goto fail;}
    void *output=malloc((size_t)size);if(!output){(*env)->ReleaseByteArrayElements(env,compressed,input,JNI_ABORT);goto fail;}
    size_t n=ZSTD_decompress(output,(size_t)size,input,input_size);
    (*env)->ReleaseByteArrayElements(env,compressed,input,JNI_ABORT);
    if(ZSTD_isError(n)||n!=size){free(output);goto fail;}
    jbyteArray result=(*env)->NewByteArray(env,(jsize)n);
    if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)n,output);free(output);return result;
fail:
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),"invalid or excessive zstd frame");return NULL;
}