// ISpooferService.aidl
package com.quimodotcom.blackboxcure.Services;

import com.quimodotcom.blackboxcure.MultipleRoutesInfo;

interface ISpooferService {
    void attachRoutes(inout List<MultipleRoutesInfo> points);
    void setPause(boolean pause);
    boolean isPaused();
    List<MultipleRoutesInfo> getRoutes();
}
