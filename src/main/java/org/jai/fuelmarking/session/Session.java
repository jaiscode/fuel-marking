package org.jai.fuelmarking.session;

import org.jai.fuelmarking.model.Records.*;

/** Who is signed in, at which site, and the site's open shift (if any). */
public final class Session {
    private static final Session INSTANCE = new Session();
    private Site site;
    private AppUser user;
    private Shift shift;

    private Session() {}

    public static Session get() { return INSTANCE; }

    public Site site() { return site; }
    public AppUser user() { return user; }
    public Shift shift() { return shift; }

    public void setSite(Site site) { this.site = site; }
    public void setUser(AppUser user) { this.user = user; }
    public void setShift(Shift shift) { this.shift = shift; }
}
