/*
 * This file is part of MultiROM Manager.
 *
 * MultiROM Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MultiROM Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MultiROM Manager. If not, see <http://www.gnu.org/licenses/>.
 */

package com.tassadar.multirommgr;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.chainfire.libsuperuser.Shell;

public class MultiROM {
    // Minimum MultiROM version which is able to boot ROMs
    // via --boot-rom argument
    public static final String MIN_BOOT_ROM_VER = "19g";

    public static final int MAX_ROM_NAME = 26;
    public static final String INTERNAL_ROM = "Internal";
    private static final String UTOUCH_ROM_INFO = "ubuntu_touch.txt";

    public boolean findMultiROMDir() {
        List<String> out = Shell.SU.run(
                "folders=\"/data/media/0/multirom/ /data/media/multirom/\";" +
                "for f in $folders; do" +
                "    if [ -d \"$f\" ]; then" +
                "        echo \"$f\";" +
                "        exit 0;" +
                "    fi;" +
                "done;");

        if (out == null || out.isEmpty())
            return false;

        m_path = out.get(0);
        Log.d("MultiROM", "Found in path " + m_path);
        return true;
    }

    public boolean findVersion() {
        List<String> out = Shell.SU.run(m_path + "multirom -v");
        if (out == null || out.isEmpty())
            return false;

        m_version = out.get(0);
        Log.d("MultiROM", "MultiROM version: " + m_version);
        return true;
    }

    private String findInternalRomName(String bb) {
        List<String> out = Shell.SU.run(bb + " cat \"" + m_path + "/multirom.ini\"");
        if (out == null || out.isEmpty())
            return INTERNAL_ROM;

        String entry;
        for(int i = 0; i < out.size(); ++i) {
            entry = out.get(i).trim();
            if(entry.startsWith("int_display_name="))
                return entry.substring(17); // strlen("int_display_name=");
        }
        return INTERNAL_ROM;
    }

    public void findRoms() {
        String b = Utils.extractAsset("busybox");
        if(b == null) {
            Log.e("MultiROM", "Failed to extract busybox!");
            return;
        }

        String internal = findInternalRomName(b);

        List<String> out = Shell.SU.run(b + " ls -1 -p \"" + m_path + "/roms/\"");
        if (out == null || out.isEmpty())
            return;

        Rom rom;
        int type;
        String name;
        for(int i = 0; i < out.size(); ++i) {
            name = out.get(i);
            if(!name.endsWith("/"))
                continue;

            name = name.substring(0, name.length() - 1);
            if(name.equals(INTERNAL_ROM)) {
                name = internal;
                type = Rom.ROM_PRIMARY;
            } else {
                type = Rom.ROM_SECONDARY;
            }

            rom = new Rom(name, type);
            m_roms.add(rom);
        }

        Collections.sort(m_roms, new Rom.NameComparator());

        loadRomIconData(b);
    }

    private void loadRomIconData(String b) {
        // Load icon data
        List<String> out = Shell.SU.run(
                "cd \"%s/roms\"; " +
                "for d in $(\"%s\" ls -1); do " +
                "    ([ ! -d \"$d\" ]) && continue;" +
                "    ([ ! -f \"$d/.icon_data\" ]) && continue;" +
                "    echo \"ROM:$d\";" +
                "    cat \"$d/.icon_data\";" +
                "done;",
                m_path, b);

        if (out == null || out.isEmpty())
            return;

        Resources res = MultiROMMgrApplication.getAppContext().getResources();
        Rom rom;
        String line;
        int type;
        Set<String> presentHashes = new HashSet<String>();
        for(int i = 0; i+2 < out.size(); ++i) {
            rom = null;
            line = out.get(i);

            if(!line.startsWith("ROM:"))
                continue;

            line = line.substring(4);

            type = line.equals(INTERNAL_ROM) ? Rom.ROM_PRIMARY : Rom.ROM_SECONDARY;
            for(Rom r : m_roms) {
                if (r.type == type && (type == Rom.ROM_PRIMARY || line.equals(r.name)))
                    rom = r;
            }

            if(rom == null)
                continue;

            line = out.get(++i);
            if(line.equals("predef_set")) {
                line = out.get(++i);
                rom.icon_id = res.getIdentifier(line, null, null);
                rom.icon_hash = null;
            } else if(line.equals("user_defined")) {
                rom.icon_id = R.id.user_defined_icon;
                rom.icon_hash = out.get(++i);

                presentHashes.add(rom.icon_hash);
            }
        }

        deleteUnusedIcons(presentHashes);
    }

    public void deleteUnusedIcons(Set<String> usedIconHashes) {
        String hash;
        File iconDir = new File(MultiROMMgrApplication.getAppContext().getCacheDir(), "icons");
        File[] files = iconDir.listFiles();
        for(File f : files) {
            hash = f.getName();
            hash = hash.substring(0, hash.length()-4); // remove .png
            if(!usedIconHashes.contains(hash))
                f.delete();
        }
    }

    public void renameRom(Rom rom, String new_name) {
        if(rom.type == Rom.ROM_PRIMARY) {
            String b = Utils.extractAsset("busybox");
            if(b == null) {
                Log.e("MultiROM", "Failed to extract busybox!");
                return;
            }

            Shell.SU.run(
                    "cd \"%s\";" +
                    "if [ \"$(%s grep 'int_display_name=.*' multirom.ini)\" ]; then" +
                    "    %s sed -i -e \"s/int_display_name=.*/int_display_name=%s/g\" multirom.ini;" +
                    "else" +
                    "    echo \"int_display_name=%s\" >> multirom.ini;" +
                    "fi",
                    m_path, b, b, new_name, new_name);
        } else {
            Shell.SU.run("cd \"%s/roms/\" && mv \"%s\" \"%s\"", m_path, rom.name, new_name);
        }
    }

    public void eraseROM(Rom rom) {
        if(rom.type == Rom.ROM_PRIMARY) {
            Log.e("MultiROM", "Attempted to delete primary ROM!");
            return;
        }

        String b = Utils.extractAsset("busybox");
        if(b == null) {
            Log.e("MultiROM", "Failed to extract busybox!");
            return;
        }

        Shell.SU.run("%s chattr -R -i \"%s/roms/%s\"; %s rm -rf \"%s/roms/%s\"",
                b, m_path, rom.name, b, m_path, rom.name);
    }

    public void bootRom(Rom rom) {
        String name = (rom.type == Rom.ROM_PRIMARY) ? INTERNAL_ROM : rom.name;
        Shell.SU.run("%s/multirom --boot-rom=\"%s\"", m_path, name);
    }

    public boolean isKexecNeededFor(Rom rom) {
        if(rom.type == Rom.ROM_PRIMARY)
            return false;

        // if android ROM check for boot.img, else kexec
        List<String> out = Shell.SU.run(String.format(
                "cd \"%s/roms/%s\"; " +
                "if [ -d boot ] && [ -d system ] && [ -d data ] && [ -d cache ]; then" +
                "    if [ -e boot.img ]; then" +
                "        echo kexec;" +
                "    else" +
                "        echo normal;" +
                "    fi;" +
                "else" +
                "    echo kexec;" +
                "fi;",
                m_path, rom.name));

        if (out == null || out.isEmpty()) {
            Log.e("MultiROM", "Failed to check for kexec in ROM " + rom.name);
            return true;
        }

        return out.get(0).equals("kexec");
    }

    public boolean hasBootRomReqMultiROM() {
        int[] my = parseMultiRomVersions(m_version);
        int[] req = parseMultiRomVersions(MultiROM.MIN_BOOT_ROM_VER);
        return (my[0] > req[0]) || (my[0] == req[0] && my[1] >= req[1]);
    }

    public static int[] parseMultiRomVersions(String ver) {
        int[] res = { 0, 0 };
        if(!Utils.isNumeric(ver.charAt(ver.length()-1))) {
            res[1] = (int)ver.charAt(ver.length()-1);
            ver = ver.substring(0, ver.length()-1);
        }
        try {
            res[0] = Integer.valueOf(ver);
        } catch(NumberFormatException e) {
            e.printStackTrace();
        }
        return res;
    }

    public String getNewRomFolder(String base) {
        if(base.length() > MAX_ROM_NAME)
            base = base.substring(0, MAX_ROM_NAME);

        List<String> out = Shell.SU.run(String.format(
                "cd \"%s/roms\"; " +
                "rom=\"%s\"; c=0; " +
                "while [ $c -lt 10 ]; do" +
                "    if [ ! -d \"$rom\" ]; then" +
                "        echo $(pwd)/$rom;" +
                "        exit 0;" +
                "    fi;" +
                "    c=$(($c+1));" +
                "    rom=\"${rom%%?}$c\";" +
                "done",
                m_path, base));

        if (out == null || out.isEmpty())
            return null;
        return out.get(0);
    }

    public boolean initUbuntuDir(String path) {
        List<String> out = Shell.SU.run(String.format(
                "mkdir -p \"%s\" && cd \"%s\" && " +
                "mkdir system data cache && " +
                "mkdir cache/recovery && " +
                "cat ../../infos/%s > rom_info.txt &&" +
                "echo success",
                path, path, UTOUCH_ROM_INFO));

        if (out == null || out.isEmpty() || !out.get(0).equals("success"))
            return false;
        return true;
    }

    public int getFreeSpaceMB() {
        String bb = Utils.extractAsset("busybox");

        List<String> out = Shell.SU.run("\"%s\" df -Pm \"%s\"", bb, m_path);
        if (out == null || out.size() < 2 || !out.get(0).startsWith("Filesystem"))
            return -1;

        String l = out.get(1);
        if(!l.startsWith("/dev"))
            return -1;

        String[] tokens = l.split("[ \t]+");
        if(tokens.length != 6)
            return -1;

        try {
            return Integer.parseInt(tokens[3]);
        } catch(NumberFormatException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void setRomIcon(Rom rom, String path) {
        String hash = Utils.calculateSHA256(path);
        if(hash == null)
            return;

        FileOutputStream out = null;
        try {
            File destDir = new File(MultiROMMgrApplication.getAppContext().getCacheDir(), "icons");
            destDir.mkdirs();

            out = new FileOutputStream(new File(destDir, hash + ".png"));

            Bitmap b = Utils.resizeBitmap(BitmapFactory.decodeFile(path), 64, 64);
            b.compress(Bitmap.CompressFormat.PNG, 0, out);

            storeRomIcon(rom, R.id.user_defined_icon, hash);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if(out != null)
                try { out.close(); } catch(Exception e) { }
        }
    }

    public void setRomIcon(Rom rom, int drawableId) {
        storeRomIcon(rom, drawableId, null);
    }

    private void storeRomIcon(Rom rom, int icon_id, String hash) {
        String name = rom.name;
        if(rom.type == Rom.ROM_PRIMARY)
            name = INTERNAL_ROM;

        String data, ic_type;
        if(icon_id == R.id.user_defined_icon) {
            data = hash;
            ic_type = "user_defined";
        } else {
            Resources res = MultiROMMgrApplication.getAppContext().getResources();
            data = res.getResourceName(icon_id);
            ic_type = "predef_set";
        }

        Shell.SU.run(
                "cd \"%s/roms/%s\" && " +
                "echo '%s' > .icon_data &&" +
                "echo '%s' >> .icon_data"
                , m_path, name, ic_type, data);

        rom.icon_id = icon_id;
        rom.icon_hash = hash;
        rom.resetIconDrawable();
    }

    public String getVersion() {
        return m_version;
    }
    public String getPath() { return m_path; }
    public ArrayList<Rom> getRoms() { return m_roms; }

    private String m_path;
    private String m_version;
    private ArrayList<Rom> m_roms = new ArrayList<Rom>();
}
