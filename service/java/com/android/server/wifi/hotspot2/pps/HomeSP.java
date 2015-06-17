package com.android.server.wifi.hotspot2.pps;

import android.util.Log;

import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.DomainNameElement;
import com.android.server.wifi.anqp.NAIRealmElement;
import com.android.server.wifi.anqp.RoamingConsortiumElement;
import com.android.server.wifi.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.AuthMatch;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointMatch;
import com.android.server.wifi.hotspot2.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.android.server.wifi.anqp.Constants.ANQPElementType;

public class HomeSP {
    private final Map<String, Long> mSSIDs;        // SSID, HESSID, [0,N]
    private final String mFQDN;
    private final DomainMatcher mDomainMatcher;
    private final Set<String> mOtherHomePartners;
    private final HashSet<Long> mRoamingConsortiums;    // [0,N]
    private final Set<Long> mMatchAnyOIs;           // [0,N]
    private final List<Long> mMatchAllOIs;          // [0,N]

    private final Credential mCredential;

    // Informational:
    private final String mFriendlyName;             // [1]
    private final String mIconURL;                  // [0,1]

    public HomeSP(Map<String, Long> ssidMap,
                   /*@NotNull*/ String fqdn,
                   /*@NotNull*/ HashSet<Long> roamingConsortiums,
                   /*@NotNull*/ Set<String> otherHomePartners,
                   /*@NotNull*/ Set<Long> matchAnyOIs,
                   /*@NotNull*/ List<Long> matchAllOIs,
                   String friendlyName,
                   String iconURL,
                   Credential credential) {

        mSSIDs = ssidMap;
        List<List<String>> otherPartners = new ArrayList<List<String>>(otherHomePartners.size());
        for (String otherPartner : otherHomePartners) {
            otherPartners.add(Utils.splitDomain(otherPartner));
        }
        mOtherHomePartners = otherHomePartners;
        mFQDN = fqdn;
        mDomainMatcher = new DomainMatcher(Utils.splitDomain(fqdn), otherPartners);
        mRoamingConsortiums = roamingConsortiums;
        mMatchAnyOIs = matchAnyOIs;
        mMatchAllOIs = matchAllOIs;
        mFriendlyName = friendlyName;
        mIconURL = iconURL;
        mCredential = credential;
    }

    public HomeSP getClone(String password) {
        if (getCredential().hasDisregardPassword()) {
            return new HomeSP(mSSIDs,
                    mFQDN,
                    mRoamingConsortiums,
                    mOtherHomePartners,
                    mMatchAnyOIs,
                    mMatchAllOIs,
                    mFriendlyName,
                    mIconURL,
                    new Credential(mCredential, password));
        }
        else {
            return this;
        }
    }

    public PasspointMatch match(NetworkDetail networkDetail,
                                Map<ANQPElementType, ANQPElement> anqpElementMap,
                                SIMAccessor simAccessor) {

        PasspointMatch spMatch = matchSP(networkDetail, anqpElementMap, simAccessor);

        if (spMatch == PasspointMatch.HomeProvider || spMatch == PasspointMatch.RoamingProvider) {
            NAIRealmElement naiRealmElement =
                    (NAIRealmElement) anqpElementMap.get(ANQPElementType.ANQPNAIRealm);
            ThreeGPPNetworkElement threeGPPNetworkElement =
                    (ThreeGPPNetworkElement) anqpElementMap.get(ANQPElementType.ANQP3GPPNetwork);

            AuthMatch authMatch = naiRealmElement.match(mCredential, threeGPPNetworkElement);
            Log.d(Utils.hs2LogTag(getClass()), networkDetail.toKeyString() + " match on " + mFQDN +
                    ": " + spMatch + ", auth " + authMatch);
            return authMatch == AuthMatch.None ? PasspointMatch.None : spMatch;
        }
        else {
            return spMatch;
        }
    }

    public PasspointMatch matchSP(NetworkDetail networkDetail,
                                Map<ANQPElementType, ANQPElement> anqpElementMap,
                                SIMAccessor simAccessor) {

        if (mSSIDs.containsKey(networkDetail.getSSID())) {
            Long hessid = mSSIDs.get(networkDetail.getSSID());
            if (hessid == null || networkDetail.getHESSID() == hessid) {
                Log.d(Utils.hs2LogTag(getClass()), "match SSID");
                return PasspointMatch.HomeProvider;
            }
        }

        Set<Long> anOIs = new HashSet<>();

        if (networkDetail.getRoamingConsortiums() != null) {
            for (long oi : networkDetail.getRoamingConsortiums()) {
                anOIs.add(oi);
            }
        }
        RoamingConsortiumElement rcElement = anqpElementMap != null ?
                (RoamingConsortiumElement) anqpElementMap.get(ANQPElementType.ANQPRoamingConsortium)
                : null;
        if (rcElement != null) {
            anOIs.addAll(rcElement.getOIs());
        }

        // It may seem reasonable to check for home provider match prior to checking for roaming
        // relationship, but it is possible to avoid an ANQP query if it turns out that the
        // "match all" rule fails based only on beacon info only.
        boolean roamingMatch = false;

        if (!mMatchAllOIs.isEmpty()) {
            boolean matchesAll = true;

            for (long spOI : mMatchAllOIs) {
                if (!anOIs.contains(spOI)) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) {
                roamingMatch = true;
            }
            else {
                if (anqpElementMap != null || networkDetail.getAnqpOICount() == 0) {
                    return PasspointMatch.Declined;
                }
                else {
                    return PasspointMatch.Incomplete;
                }
            }
        }

        if (!roamingMatch &&
                (!Collections.disjoint(mMatchAnyOIs, anOIs) ||
                        !Collections.disjoint(mRoamingConsortiums, anOIs))) {
            roamingMatch = true;
        }

        if (anqpElementMap == null) {
            return PasspointMatch.Incomplete;
        }

        DomainNameElement domainNameElement =
                (DomainNameElement) anqpElementMap.get(ANQPElementType.ANQPDomName);

        if (domainNameElement != null) {
            for (String domain : domainNameElement.getDomains()) {
                List<String> anLabels = Utils.splitDomain(domain);
                DomainMatcher.Match match = mDomainMatcher.isSubDomain(anLabels);
                if (match != DomainMatcher.Match.None) {
                    return PasspointMatch.HomeProvider;
                }

                /* This is fundamentally wrong: We can't match the ANQP data to something unrelated
                 * to this Home SP. Commented out until this has been clarified by the WFA.
                String imsi = simAccessor.getMatchingImsi(Utils.getMccMnc(anLabels));
                if (imsi != null) {
                    Log.d(Utils.hs2LogTag(getClass()), "Domain " + domain +
                            " matches IMSI " + imsi);
                    return PasspointMatch.HomeProvider;
                }
                */
            }
        }

        return roamingMatch ? PasspointMatch.RoamingProvider : PasspointMatch.None;
    }

    public String getFQDN() { return mFQDN; }
    public String getFriendlyName() { return mFriendlyName; }
    public HashSet<Long> getRoamingConsortiums() { return mRoamingConsortiums; }
    public Credential getCredential() { return mCredential; }

    public Map<String, Long> getSSIDs() {
        return mSSIDs;
    }

    public Collection<String> getOtherHomePartners() {
        return mOtherHomePartners;
    }

    public Set<Long> getMatchAnyOIs() {
        return mMatchAnyOIs;
    }

    public List<Long> getMatchAllOIs() {
        return mMatchAllOIs;
    }

    public String getIconURL() {
        return mIconURL;
    }

    public boolean deepEquals(HomeSP other) {
        return mFQDN.equals(other.mFQDN) &&
                mSSIDs.equals(other.mSSIDs) &&
                mOtherHomePartners.equals(other.mOtherHomePartners) &&
                mRoamingConsortiums.equals(other.mRoamingConsortiums) &&
                mMatchAnyOIs.equals(other.mMatchAnyOIs) &&
                mMatchAllOIs.equals(other.mMatchAllOIs) &&
                mFriendlyName.equals(other.mFriendlyName) &&
                Utils.compare(mIconURL, other.mIconURL) == 0 &&
                mCredential.equals(other.mCredential);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        } else if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        HomeSP that = (HomeSP) thatObject;
        return mFQDN.equals(that.mFQDN);
    }

    @Override
    public int hashCode() {
        return mFQDN.hashCode();
    }

    @Override
    public String toString() {
        return "HomeSP{" +
                "mSSIDs=" + mSSIDs +
                ", mFQDN='" + mFQDN + '\'' +
                ", mDomainMatcher=" + mDomainMatcher +
                ", mRoamingConsortiums={" + Utils.roamingConsortiumsToString(mRoamingConsortiums) +
                '}' +
                ", mMatchAnyOIs={" + Utils.roamingConsortiumsToString(mMatchAnyOIs) + '}' +
                ", mMatchAllOIs={" + Utils.roamingConsortiumsToString(mMatchAllOIs) + '}' +
                ", mCredential=" + mCredential +
                ", mFriendlyName='" + mFriendlyName + '\'' +
                ", mIconURL='" + mIconURL + '\'' +
                '}';
    }
}
