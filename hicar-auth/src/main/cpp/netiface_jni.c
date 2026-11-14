/*
 * Reads a network interface's hardware address from the kernel.
 *
 * Why this exists: `NetworkInterface.getHardwareAddress()` is masked by the Android framework for
 * ordinary apps — since Android 6 it returns 02:00:00:00:00:00 for the Wi-Fi Direct interface, which
 * is not the address the access point broadcasts. HiCar's credential string needs the real one; a
 * placeholder makes the phone fail to find the network, and the failure then looks like a phone-side
 * problem.
 *
 * getifaddrs() asks the kernel directly (an RTM_GETLINK dump), so it returns the same address
 * `ip link` shows for that interface, including the per-group randomised address Android uses for
 * Wi-Fi Direct. Reading it needs no privilege: enumerating interfaces is allowed for apps.
 */
#include <ifaddrs.h>
#include <jni.h>
#include <net/if.h>
#include <netpacket/packet.h>
#include <stdio.h>
#include <string.h>

#define MAC_TEXT_LENGTH 18

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_hicarauth_NetInterfaceMac_nativeRead(JNIEnv *env, jclass ignored, jstring name)
{
    (void)ignored;
    if (env == NULL || name == NULL) {
        return NULL;
    }
    const char *requested = (*env)->GetStringUTFChars(env, name, NULL);
    if (requested == NULL) {
        return NULL;
    }

    char text[MAC_TEXT_LENGTH];
    text[0] = '\0';
    struct ifaddrs *list = NULL;
    if (getifaddrs(&list) == 0) {
        for (const struct ifaddrs *entry = list; entry != NULL; entry = entry->ifa_next) {
            /* AF_PACKET entries carry sockaddr_ll, the only place the link address is exposed. */
            if (entry->ifa_addr == NULL || entry->ifa_addr->sa_family != AF_PACKET) {
                continue;
            }
            if (strncmp(entry->ifa_name, requested, IF_NAMESIZE) != 0) {
                continue;
            }
            const struct sockaddr_ll *link = (const struct sockaddr_ll *)entry->ifa_addr;
            if (link->sll_halen != 6) {
                continue;
            }
            snprintf(text, sizeof(text), "%02x:%02x:%02x:%02x:%02x:%02x",
                link->sll_addr[0], link->sll_addr[1], link->sll_addr[2],
                link->sll_addr[3], link->sll_addr[4], link->sll_addr[5]);
            break;
        }
        freeifaddrs(list);
    }
    (*env)->ReleaseStringUTFChars(env, name, requested);

    if (text[0] == '\0') {
        return NULL;
    }
    return (*env)->NewStringUTF(env, text);
}
