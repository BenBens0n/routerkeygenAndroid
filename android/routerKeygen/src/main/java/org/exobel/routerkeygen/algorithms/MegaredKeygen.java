/*
 * Copyright 2012 Rui Araújo, Luís Fonseca
 *
 * This file is part of Router Keygen.
 *
 * Router Keygen is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Router Keygen is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Router Keygen.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.exobel.routerkeygen.algorithms;

import android.os.Parcel;
import android.os.Parcelable;

import org.exobel.routerkeygen.R;

import java.util.List;

/*
 * This is not actual an algorithm as
 * it is just the mac address without the first two characters.
 */
public class MegaredKeygen extends Keygen {

    public static final Parcelable.Creator<MegaredKeygen> CREATOR = new Parcelable.Creator<MegaredKeygen>() {
        public MegaredKeygen createFromParcel(Parcel in) {
            return new MegaredKeygen(in);
        }

        public MegaredKeygen[] newArray(int size) {
            return new MegaredKeygen[size];
        }
    };

    public MegaredKeygen(String ssid, String mac) {
        super(ssid, mac);
    }

    private MegaredKeygen(Parcel in) {
        super(in);
    }

    @Override
    public List<String> getKeys() {
        if (getMacAddress().length() != 12) {
            setErrorCode(R.string.msg_errpirelli);
            return null;
        }
        addPassword(getMacAddress().substring(2));
        return getResults();
    }


}