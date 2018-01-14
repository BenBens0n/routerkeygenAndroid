#include <android/log.h>
#include <ctype.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include "upc_keys_wrapper.h"
#include "upc_keys.h"
#include "upc_ubee.h"
#include "md5.h"

// Logging
#define LOG_TAG "upc_keys"
#define DPRINTF(...)  __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)
#define IPRINTF(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define EPRINTF(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

JNIEXPORT void JNICALL Java_org_exobel_routerkeygen_algorithms_UpcKeygen_upcNative
        (JNIEnv * env, jobject obj, jbyteArray ess, jboolean is5g)
{
  // Get stopRequested - cancellation flag.
  jclass cls = (*env)->GetObjectClass(env, obj);
  jfieldID fid_s = (*env)->GetFieldID(env, cls, "stopRequested", "Z");
  if (fid_s == NULL) {
    return; /* exception already thrown */
  }
  unsigned char stop = (*env)->GetBooleanField(env, obj, fid_s);

  // Monitoring methods
  jmethodID on_key_computed = (*env)->GetMethodID(env, cls, "onKeyComputed", "(Ljava/lang/String;)V");
  jmethodID on_progressed = (*env)->GetMethodID(env, cls, "onProgressed", "(D)V");
  if (on_key_computed == NULL || on_progressed == NULL){
    return;
  }

  // ESSID reading from parameter.
  jbyte *e_native = (*env)->GetByteArrayElements(env, ess, 0);
  jsize e_ssid_len = (*env)->GetArrayLength(env, ess);
  char * e_ssid = (char*) e_native;
  char e_ssid_nullterm[24];
  strncpy(e_ssid_nullterm, e_ssid, e_ssid_len);

  // Mode 1 - 24GHz, 2 - 5GHz
  int mode = is5g == JNI_TRUE ? 2 : 1;

  // Definitions.
  MD5_CTX ctx;
  uint8_t message_digest[20];
  uint32_t buf[4], target;
  char serial[64];
  char serial_input[64];
  char pass[9], tmpstr[17];
  uint8_t h1[16], h2[16];
  uint32_t hv[4], w1, w2, i, cnt=0, pidx;
  const char * serial_prefixes[] = { "SAAP", "SAPP", "SBAP", "UAAP" };
  const int prefixes_cnt = (sizeof(serial_prefixes)/sizeof(serial_prefixes[0]));

  target = strtoul(e_ssid_nullterm + 3, NULL, 0);
  IPRINTF("Computing UPC keys for essid [%s], target %lu, ssidlen: %d", e_ssid_nullterm, (unsigned long)target, (int)e_ssid_len);
  unsigned long stop_ctr = 0;
  unsigned long iter_ctr = 0;

  // Compute - from upc_keys.c
  for (buf[0] = 0; buf[0] <= MAX0; buf[0]++) {
    for (buf[1] = 0; buf[1] <= MAX1; buf[1]++) {
      for (buf[2] = 0; buf[2] <= MAX2; buf[2]++) {
        for (buf[3] = 0; buf[3] <= MAX3; buf[3]++) {
          // Check cancellation signal & progress monitoring.
          stop_ctr += 1;
          iter_ctr += 1;
          if (stop_ctr > (MAX_ITERATIONS/2000)){
            stop_ctr = 0;
            stop = (*env)->GetBooleanField(env, obj, fid_s);
            if (stop) {
              break;
            }

            double current_progress = (double)iter_ctr / MAX_ITERATIONS;
            (*env)->CallVoidMethod(env, obj, on_progressed, (jdouble)current_progress);
          }

          if (mode == 1 && upc_generate_ssid(buf, MAGIC_24GHZ) != target)
            continue;

          if (mode == 2 && upc_generate_ssid(buf, MAGIC_5GHZ) != target)
            continue;

          for(pidx=0; pidx < prefixes_cnt; ++pidx){
            cnt++;
            sprintf(serial, "%s%d%02d%d%04d", serial_prefixes[pidx], buf[0], buf[1], buf[2], buf[3]);
            memset(serial_input, 0, 64);

            if (mode == 2) {
              for(i=0; i<strlen(serial); i++) {
                serial_input[strlen(serial)-1-i] = serial[i];
              }
            } else {
              memcpy(serial_input, serial, strlen(serial));
            }

            MD5_Init(&ctx);
            MD5_Update(&ctx, serial_input, strlen(serial_input));
            MD5_Final(h1, &ctx);

            for (i = 0; i < 4; i++) {
              hv[i] = *(uint16_t * )(h1 + i * 2);
            }

            w1 = mangle(hv);

            for (i = 0; i < 4; i++) {
              hv[i] = *(uint16_t * )(h1 + 8 + i * 2);
            }

            w2 = mangle(hv);

            sprintf(tmpstr, "%08X%08X", w1, w2);

            MD5_Init(&ctx);
            MD5_Update(&ctx, tmpstr, strlen(tmpstr));
            MD5_Final(h2, &ctx);

            hash2pass(h2, pass);
            IPRINTF("  -> #%02d WPA2 phrase for '%s' = '%s'", cnt, serial, pass);

            jstring jpass = (*env)->NewStringUTF(env, pass);
            (*env)->CallVoidMethod(env, obj, on_key_computed, jpass);
            (*env)->DeleteLocalRef(env, jpass);
          }
        }
      }
    }
  }
}

JNIEXPORT jstring JNICALL Java_org_exobel_routerkeygen_algorithms_UpcKeygen_upcUbeeSsid
        (JNIEnv * env, jobject obj, jbyteArray ess)
{
  // MAC reading from parameter.
  jbyte *e_native = (*env)->GetByteArrayElements(env, ess, 0);
  char * e_mac = (char*) e_native;
  unsigned char ssid[100];

  ubee_generate_ssid((unsigned char *)e_mac, ssid, NULL);
  return (*env)->NewStringUTF(env, (char*)ssid);
}

JNIEXPORT jstring JNICALL Java_org_exobel_routerkeygen_algorithms_UpcKeygen_upcUbeePass
      (JNIEnv * env, jobject obj, jbyteArray ess)
{
  // MAC reading from parameter.
  jbyte *e_native = (*env)->GetByteArrayElements(env, ess, 0);
  char * e_mac = (char*) e_native;
  unsigned char pass[100];

  ubee_generate_pass((unsigned char *)e_mac, pass, NULL);
  return (*env)->NewStringUTF(env, (char*)pass);
}

