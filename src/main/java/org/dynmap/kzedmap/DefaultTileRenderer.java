package org.dynmap.kzedmap;

import static org.dynmap.JSONUtils.a;
import static org.dynmap.JSONUtils.s;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;

import javax.imageio.ImageIO;

import org.dynmap.Client;
import org.dynmap.Color;
import org.dynmap.ColorScheme;
import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.MapManager;
import org.dynmap.DynmapCore.CompassMode;
import org.dynmap.MapType.ImageFormat;
import org.dynmap.TileHashManager;
import org.dynmap.common.BiomeMap;
import org.dynmap.debug.Debug;
import org.dynmap.utils.DynmapBufferedImage;
import org.dynmap.utils.FileLockManager;
import org.dynmap.utils.MapChunkCache;
import org.dynmap.utils.MapIterator;
import org.dynmap.utils.MapIterator.BlockStep;
import org.json.simple.JSONObject;

public class DefaultTileRenderer implements MapTileRenderer {
    protected static final Color translucent = new Color(0, 0, 0, 0);
    protected String name;
    protected String prefix;
    protected int maximumHeight = 127;
    protected ColorScheme colorScheme;

    protected HashSet<Integer> highlightBlocks = new HashSet<Integer>();
    protected Color highlightColor = new Color(255, 0, 0);

    protected int   shadowscale[];  /* index=skylight level, value = 256 * scaling value */
    protected int   lightscale[];   /* scale skylight level (light = lightscale[skylight] */
    protected boolean night_and_day;    /* If true, render both day (prefix+'-day') and night (prefix) tiles */
    protected boolean transparency; /* Is transparency support active? */

    private String title;
    private String icon;
    private String bg_cfg;
    private String bg_day_cfg;
    private String bg_night_cfg;
    private int mapzoomin;
    private double shadowstrength;
    private int ambientlight;
    
    public enum BiomeColorOption {
        NONE, BIOME, TEMPERATURE, RAINFALL
    }
    protected BiomeColorOption biomecolored = BiomeColorOption.NONE; /* Use biome for coloring */

    @Override
    public String getPrefix() {
        return prefix;
    }

    public String getName() {
        return name;
    }

    public boolean isNightAndDayEnabled() { return night_and_day; }

    public DefaultTileRenderer(DynmapCore core, ConfigurationNode configuration) {
        name = configuration.getString("name", null);
        prefix = configuration.getString("prefix", name);
        maximumHeight = configuration.getInteger("maximumheight", 127);
        shadowstrength = configuration.getDouble("shadowstrength", 0.0);
        if(shadowstrength > 0.0) {
            shadowscale = new int[16];
            shadowscale[15] = 256;
            /* Normal brightness weight in MC is a 20% relative dropoff per step */
            for(int i = 14; i >= 0; i--) {
                double v = shadowscale[i+1] * (1.0 - (0.2 * shadowstrength));
                shadowscale[i] = (int)v;
                if(shadowscale[i] > 256) shadowscale[i] = 256;
                if(shadowscale[i] < 0) shadowscale[i] = 0;
            }
        }
        ambientlight = configuration.getInteger("ambientlight", 15);
        if(ambientlight < 15) {
            lightscale = new int[16];
            for(int i = 0; i < 16; i++) {
                if(i < (15-ambientlight))
                    lightscale[i] = 0;
                else
                    lightscale[i] = i - (15-ambientlight);
            }
        }
        colorScheme = ColorScheme.getScheme(core, (String)configuration.get("colorscheme"));
        night_and_day = configuration.getBoolean("night-and-day", false);
        transparency = configuration.getBoolean("transparency", true);  /* Default on */
        String biomeopt = configuration.getString("biomecolored", "none");
        if(biomeopt.equals("biome")) {
            biomecolored = BiomeColorOption.BIOME;
        }
        else if(biomeopt.equals("temperature")) {
            biomecolored = BiomeColorOption.TEMPERATURE;
        }
        else if(biomeopt.equals("rainfall")) {
            biomecolored = BiomeColorOption.RAINFALL;
        }
        else {
            biomecolored = BiomeColorOption.NONE;
        }
        
        title = configuration.getString("title");
        icon = configuration.getString("icon");
        bg_cfg = configuration.getString("background");
        bg_day_cfg = configuration.getString("backgroundday");
        bg_night_cfg = configuration.getString("backgroundnight");
        mapzoomin = configuration.getInteger("mapzoomin", 2);
    }
    
    @Override
    public ConfigurationNode saveConfiguration() {
        ConfigurationNode cn = new ConfigurationNode();
        cn.put("class", this.getClass().getName());
        cn.put("name", name);
        if(title != null)
            cn.put("title", title);
        if(icon != null)
            cn.put("icon", icon);
        cn.put("maximumheight", maximumHeight);
        cn.put("shadowstrength", shadowstrength);
        cn.put("ambientlight", ambientlight);
        if(colorScheme != null)
            cn.put("colorscheme", colorScheme.name);
        cn.put("night-and-day", night_and_day);
        cn.put("transparency", transparency);
        String bcolor = "none";
        switch(biomecolored) {
        case BIOME:
            bcolor = "biome";
            break;
        case TEMPERATURE:
            bcolor = "temperature";
            break;
        case RAINFALL:
            bcolor = "rainfall";
            break;
        }
        cn.put("biomecolored", bcolor);
        if(bg_cfg != null)
            cn.put("background", bg_cfg);
        if(bg_day_cfg != null)
            cn.put("backgroundday", bg_day_cfg);
        if(bg_night_cfg != null)
            cn.put("backgroundnight", bg_night_cfg);
        cn.put("mapzoomin", mapzoomin);

        return cn;
    }

    public boolean isBiomeDataNeeded() { return biomecolored.equals(BiomeColorOption.BIOME); }
    public boolean isRawBiomeDataNeeded() { 
        return biomecolored.equals(BiomeColorOption.RAINFALL) || biomecolored.equals(BiomeColorOption.TEMPERATURE);
    }

    public boolean render(MapChunkCache cache, KzedMapTile tile, File outputFile) {
        DynmapWorld world = tile.getDynmapWorld();
        boolean isnether = world.isNether();
        DynmapBufferedImage im = DynmapBufferedImage.allocateBufferedImage(KzedMap.tileWidth, KzedMap.tileHeight);
        DynmapBufferedImage zim = DynmapBufferedImage.allocateBufferedImage(KzedMap.tileWidth/2, KzedMap.tileHeight/2);
        
        DynmapBufferedImage im_day = null;
        DynmapBufferedImage zim_day = null;
        if(night_and_day) {
            im_day = DynmapBufferedImage.allocateBufferedImage(KzedMap.tileWidth, KzedMap.tileHeight);
            zim_day = DynmapBufferedImage.allocateBufferedImage(KzedMap.tileWidth/2, KzedMap.tileHeight/2);
        }

        int ix = KzedMap.anchorx + tile.px / 2 + tile.py / 2 - ((127-maximumHeight)/2);
        int iy = maximumHeight;
        int iz = KzedMap.anchorz + tile.px / 2 - tile.py / 2 + ((127-maximumHeight)/2);

        /* Don't mess with existing height-clipped renders */
        if(maximumHeight < 127)
            isnether = false;

        int jx, jz;

        int x, y;

        MapIterator mapiter = cache.getIterator(ix, iy, iz);
        
        Color c1 = new Color();
        Color c2 = new Color();
        int[] argb = im.argb_buf;
        int[] zargb = zim.argb_buf;
        Color c1_day = null;
        Color c2_day = null;
        int[] argb_day = null;
        int[] zargb_day = null;
        if(night_and_day) {
            c1_day = new Color();
            c2_day = new Color();
            argb_day = im_day.argb_buf;
            zargb_day = zim_day.argb_buf;
        }
        int rowoff = 0;
        /* draw the map */
        for (y = 0; y < KzedMap.tileHeight;) {
            jx = ix;
            jz = iz;

            for (x = KzedMap.tileWidth - 1; x >= 0; x -= 2) {
                mapiter.initialize(jx, iy, jz);   
                scan(world, 0, isnether, c1, c1_day, mapiter);
                mapiter.initialize(jx, iy, jz);   
                scan(world, 2, isnether, c2, c2_day, mapiter);

                argb[rowoff+x] = c1.getARGB();
                argb[rowoff+x-1] = c2.getARGB();
                
                if(night_and_day) {
                    argb_day[rowoff+x] = c1_day.getARGB(); 
                    argb_day[rowoff+x-1] = c2_day.getARGB();
                }
                                
                jx++;
                jz++;

            }
            
            y++;
            rowoff += KzedMap.tileWidth;

            jx = ix;
            jz = iz - 1;

            for (x = KzedMap.tileWidth - 1; x >= 0; x -= 2) {
                mapiter.initialize(jx, iy, jz);   
                scan(world, 2, isnether, c1, c1_day, mapiter);
                jx++;
                jz++;
                mapiter.initialize(jx, iy, jz);   
                scan(world, 0, isnether, c2, c2_day, mapiter);

                argb[rowoff+x] = c1.getARGB();
                argb[rowoff+x-1] = c2.getARGB(); 

                if(night_and_day) {
                    argb_day[rowoff+x] = c1_day.getARGB();
                    argb_day[rowoff+x-1] = c2_day.getARGB(); 
                }
            }                
            y++;
            rowoff += KzedMap.tileWidth;

            ix++;
            iz--;
        }
        /* Now, compute zoomed tile - bilinear filter 2x2 -> 1x1 */
        doScaleWithBilinear(argb, zargb, KzedMap.tileWidth, KzedMap.tileHeight);
        if(night_and_day) {
            doScaleWithBilinear(argb_day, zargb_day, KzedMap.tileWidth, KzedMap.tileHeight);
        }

        /* Hand encoding and writing file off to MapManager */
        KzedZoomedMapTile zmtile = new KzedZoomedMapTile(tile.getDynmapWorld(), tile);
        File zoomFile = MapManager.mapman.getTileFile(zmtile);

        return doFileWrites(outputFile, tile, im, im_day, zmtile, zoomFile, zim, zim_day);
    }

    private void doScaleWithBilinear(int[] argb, int[] zargb, int width, int height) {
        Color c1 = new Color();
        /* Now, compute zoomed tile - bilinear filter 2x2 -> 1x1 */
        for(int y = 0; y < height; y += 2) {
            for(int x = 0; x < width; x += 2) {
                int red = 0;
                int green = 0;
                int blue = 0;
                int alpha = 0;
                for(int yy = y; yy < y+2; yy++) {
                    for(int xx = x; xx < x+2; xx++) {
                        c1.setARGB(argb[(yy*width)+xx]);
                        red += c1.getRed();
                        green += c1.getGreen();
                        blue += c1.getBlue();
                        alpha += c1.getAlpha();
                    }
                }
                c1.setRGBA(red>>2, green>>2, blue>>2, alpha>>2);
                zargb[(y*width/4) + (x/2)] = c1.getARGB();
            }
        }
    }

    private boolean doFileWrites(final File fname, final KzedMapTile mtile,
        final DynmapBufferedImage img, final DynmapBufferedImage img_day, 
        final KzedZoomedMapTile zmtile, final File zoomFile,
        final DynmapBufferedImage zimg, final DynmapBufferedImage zimg_day) {
        boolean didwrite = false;
        
        /* Get coordinates of zoomed tile */
        int ox = (mtile.px == zmtile.getTileX())?0:KzedMap.tileWidth/2;
        int oy = (mtile.py == zmtile.getTileY())?0:KzedMap.tileHeight/2;

        /* Test to see if we're unchanged from older tile */
        TileHashManager hashman = MapManager.mapman.hashman;
        long crc = hashman.calculateTileHash(img.argb_buf);
        boolean updated_fname = false;
        int tx = mtile.px/KzedMap.tileWidth;
        int ty = mtile.py/KzedMap.tileHeight;
        FileLockManager.getWriteLock(fname);
        try {
            if((!fname.exists()) || (crc != hashman.getImageHashCode(mtile.getKey(prefix), null, tx, ty))) {
                Debug.debug("saving image " + fname.getPath());
                if(!fname.getParentFile().exists())
                    fname.getParentFile().mkdirs();
                try {
                    FileLockManager.imageIOWrite(img.buf_img, ImageFormat.FORMAT_PNG, fname);
                } catch (IOException e) {
                    Debug.error("Failed to save image: " + fname.getPath(), e);
                } catch (java.lang.NullPointerException e) {
                    Debug.error("Failed to save image (NullPointerException): " + fname.getPath(), e);
                }
                MapManager.mapman.pushUpdate(mtile.getDynmapWorld(), new Client.Tile(mtile.getFilename()));
                hashman.updateHashCode(mtile.getKey(prefix), null, tx, ty, crc);
                updated_fname = true;
                didwrite = true;
            }
        } finally {
            FileLockManager.releaseWriteLock(fname);
            DynmapBufferedImage.freeBufferedImage(img);
        }
        MapManager.mapman.updateStatistics(mtile, prefix, true, updated_fname, true);

        mtile.file = fname;

        boolean updated_dfname = false;
        
        File dfname = new File(mtile.getDynmapWorld().worldtilepath, mtile.getDayFilename());
        if(img_day != null) {
            crc = hashman.calculateTileHash(img.argb_buf);
            FileLockManager.getWriteLock(dfname);
            try {
                if((!dfname.exists()) || (crc != hashman.getImageHashCode(mtile.getKey(prefix), "day", tx, ty))) {
                    Debug.debug("saving image " + dfname.getPath());
                    if(!dfname.getParentFile().exists())
                        dfname.getParentFile().mkdirs();
                    try {
                        FileLockManager.imageIOWrite(img_day.buf_img, ImageFormat.FORMAT_PNG, dfname);
                    } catch (IOException e) {
                        Debug.error("Failed to save image: " + dfname.getPath(), e);
                    } catch (java.lang.NullPointerException e) {
                        Debug.error("Failed to save image (NullPointerException): " + dfname.getPath(), e);
                    }
                    MapManager.mapman.pushUpdate(mtile.getDynmapWorld(), new Client.Tile(mtile.getDayFilename()));
                    hashman.updateHashCode(mtile.getKey(prefix), "day", tx, ty, crc);
                    updated_dfname = true;
                    didwrite = true;
                }
            } finally {
                FileLockManager.releaseWriteLock(dfname);
                DynmapBufferedImage.freeBufferedImage(img_day);
            }
            MapManager.mapman.updateStatistics(mtile, prefix+"_day", true, updated_dfname, true);
        }
        
        // Since we've already got the new tile, and we're on an async thread, just
        // make the zoomed tile here
        boolean ztile_updated = false;
        FileLockManager.getWriteLock(zoomFile);
        try {
            if(updated_fname || (!zoomFile.exists())) {
                saveZoomedTile(zmtile, zoomFile, zimg, ox, oy, null);
                MapManager.mapman.pushUpdate(zmtile.getDynmapWorld(),
                                         new Client.Tile(zmtile.getFilename()));
                zmtile.getDynmapWorld().enqueueZoomOutUpdate(zoomFile);
                ztile_updated = true;
            }
        } finally {
            FileLockManager.releaseWriteLock(zoomFile);
            DynmapBufferedImage.freeBufferedImage(zimg);
        }
        MapManager.mapman.updateStatistics(zmtile, null, true, ztile_updated, true);
        
        if(zimg_day != null) {
            File zoomFile_day = new File(zmtile.getDynmapWorld().worldtilepath, zmtile.getDayFilename());
            ztile_updated = false;
            FileLockManager.getWriteLock(zoomFile_day);
            try {
                if(updated_dfname || (!zoomFile_day.exists())) {
                    saveZoomedTile(zmtile, zoomFile_day, zimg_day, ox, oy, "day");
                    MapManager.mapman.pushUpdate(zmtile.getDynmapWorld(),
                                             new Client.Tile(zmtile.getDayFilename()));            
                    zmtile.getDynmapWorld().enqueueZoomOutUpdate(zoomFile_day);
                    ztile_updated = true;
                }
            } finally {
                FileLockManager.releaseWriteLock(zoomFile_day);
                DynmapBufferedImage.freeBufferedImage(zimg_day);
            }
            MapManager.mapman.updateStatistics(zmtile, "day", true, ztile_updated, true);
        }
        return didwrite;
    }

    private void saveZoomedTile(final KzedZoomedMapTile zmtile, final File zoomFile,
            final DynmapBufferedImage zimg, int ox, int oy, String subkey) {
        BufferedImage zIm = null;
        DynmapBufferedImage kzIm = null;
        try {
            zIm = ImageIO.read(zoomFile);
        } catch (IOException e) {
        } catch (IndexOutOfBoundsException e) {
        }

        boolean zIm_allocated = false;
        if (zIm == null) {
            /* create new one */
            kzIm = DynmapBufferedImage.allocateBufferedImage(KzedMap.tileWidth, KzedMap.tileHeight);
            zIm = kzIm.buf_img;
            zIm_allocated = true;
            Debug.debug("New zoom-out tile created " + zmtile.getFilename());
        } else {
            Debug.debug("Loaded zoom-out tile from " + zmtile.getFilename());
        }

        /* blit scaled rendered tile onto zoom-out tile */
        zIm.setRGB(ox, oy, KzedMap.tileWidth/2, KzedMap.tileHeight/2, zimg.argb_buf, 0, KzedMap.tileWidth/2);

        /* save zoom-out tile */
        if(!zoomFile.getParentFile().exists())
            zoomFile.getParentFile().mkdirs();

        try {
            FileLockManager.imageIOWrite(zIm, ImageFormat.FORMAT_PNG, zoomFile);
            Debug.debug("Saved zoom-out tile at " + zoomFile.getName());
        } catch (IOException e) {
            Debug.error("Failed to save zoom-out tile: " + zoomFile.getName(), e);
        } catch (java.lang.NullPointerException e) {
            Debug.error("Failed to save zoom-out tile (NullPointerException): " + zoomFile.getName(), e);
        }

        if(zIm_allocated)
            DynmapBufferedImage.freeBufferedImage(kzIm);
        else
            zIm.flush();

    }
    protected void scan(DynmapWorld world, int seq, boolean isnether, final Color result, final Color result_day,
            MapIterator mapiter) {
        int lightlevel = 15;
        int lightlevel_day = 15;
        BiomeMap bio = null;
        double rain = 0.0;
        double temp = 0.0;
        result.setTransparent();
        if(result_day != null)
            result_day.setTransparent();
        for (;;) {
            if (mapiter.getY() < 0) {
                return;
            }
            int id = mapiter.getBlockTypeID();
            int data = 0;
            if(isnether) {    /* Make bedrock ceiling into air in nether */
                if(id != 0) {
                    /* Remember first color we see, in case we wind up solid */
                    if(result.isTransparent()) {
                        try {
                            if(colorScheme.colors[id] != null) {
                                result.setColor(colorScheme.colors[id][seq]);
                            }
                        } catch (ArrayIndexOutOfBoundsException aioobx) {
                            colorScheme.resizeColorArray(id);
                        }
                    }
                    id = 0;
                }
                else
                    isnether = false;
            }
            if(id != 0) {       /* No update needed for air */
                switch(biomecolored) {
                    case NONE:
                        try {
                            if(colorScheme.datacolors[id] != null) {    /* If data colored */
                                data = mapiter.getBlockData();
                            }
                        } catch (ArrayIndexOutOfBoundsException aioobx) {
                            colorScheme.resizeColorArray(id);
                        }
                        break;
                    case BIOME:
                        bio = mapiter.getBiome();
                        break;
                    case RAINFALL:
                        rain = mapiter.getRawBiomeRainfall();
                        break;
                    case TEMPERATURE:
                        temp = mapiter.getRawBiomeTemperature();
                        break;
                }
                if((shadowscale != null) && (mapiter.getY() < 127)) {
                    /* Find light level of previous chunk */
                    BlockStep last = mapiter.unstepPosition();
                    lightlevel = lightlevel_day = mapiter.getBlockSkyLight();
                    if(lightscale != null)
                        lightlevel = lightscale[lightlevel];
                    if((lightlevel < 15) || (lightlevel_day < 15)) {
                        int emitted = mapiter.getBlockEmittedLight();
                        lightlevel = Math.max(emitted, lightlevel);                                
                        lightlevel_day = Math.max(emitted, lightlevel_day);                                
                    }
                    mapiter.stepPosition(last);
                }
            }
            
            switch (seq) {
                case 0:
                    mapiter.stepPosition(BlockStep.X_MINUS);
                    break;
                case 1:
                case 3:
                    mapiter.stepPosition(BlockStep.Y_MINUS);
                    break;
                case 2:
                    mapiter.stepPosition(BlockStep.Z_PLUS);
                    break;
            }
            
            seq = (seq + 1) & 3;

            if (id != 0) {
                if (highlightBlocks.contains(id)) {
                    result.setColor(highlightColor);
                    return;
                }
                Color[] colors = null;
                switch(biomecolored) {
                    case NONE:
                        try {
                            if(data != 0)
                                colors = colorScheme.datacolors[id][data];
                            else
                                colors = colorScheme.colors[id];
                        } catch (ArrayIndexOutOfBoundsException aioobx) {
                            colorScheme.resizeColorArray(id);
                        }
                        break;
                    case BIOME:
                        if(bio != null)
                            colors = colorScheme.biomecolors[bio.ordinal()];
                        break;
                    case RAINFALL:
                        colors = colorScheme.getRainColor(rain);
                        break;
                    case TEMPERATURE:
                        colors = colorScheme.getTempColor(temp);
                        break;
                }
                if (colors != null) {
                    Color c = colors[seq];
                    if (c.getAlpha() > 0) {
                        /* we found something that isn't transparent, or not doing transparency */
                        if ((!transparency) || (c.getAlpha() == 255)) {
                            /* it's opaque - the ray ends here */
                            result.setARGB(c.getARGB() | 0xFF000000);
                            if(lightlevel < 15) {  /* Not full light? */
                                shadowColor(result, lightlevel);
                            }
                            if(result_day != null) {
                                if(lightlevel_day == lightlevel)    /* Same light = same result */
                                    result_day.setColor(result);
                                else {
                                    result_day.setColor(c);
                                    if(lightlevel_day < 15)
                                        shadowColor(result_day, lightlevel_day);
                                }
                            }
                            return;
                        }

                        /* this block is transparent, so recurse */
                        scan(world, seq, isnether, result, result_day, mapiter);

                        int cr = c.getRed();
                        int cg = c.getGreen();
                        int cb = c.getBlue();
                        int ca = c.getAlpha();
                        if(lightlevel < 15) {
                            int scale = shadowscale[lightlevel];
                            cr = (cr * scale) >> 8;
                            cg = (cg * scale) >> 8;
                            cb = (cb * scale) >> 8;
                        }
                        cr *= ca;
                        cg *= ca;
                        cb *= ca;
                        int na = 255 - ca;
                        result.setRGBA((result.getRed() * na + cr) >> 8, (result.getGreen() * na + cg) >> 8, (result.getBlue() * na + cb) >> 8, 255);
                        /* Handle day also */
                        if(result_day != null) {
                            cr = c.getRed();
                            cg = c.getGreen();
                            cb = c.getBlue();
                            if(lightlevel_day < 15) {
                                int scale = shadowscale[lightlevel_day];
                                cr = (cr * scale) >> 8;
                                cg = (cg * scale) >> 8;
                                cb = (cb * scale) >> 8;
                            }
                            cr *= ca;
                            cg *= ca;
                            cb *= ca;
                            result_day.setRGBA((result_day.getRed() * na + cr) >> 8, (result_day.getGreen() * na + cg) >> 8, (result_day.getBlue() * na + cb) >> 8, 
                                               255);
                        }
                        return;
                    }
                }
            }
        }
    }
    private final void shadowColor(Color c, int lightlevel) {
        int scale = shadowscale[lightlevel];
        if(scale < 256)
            c.setRGBA((c.getRed() * scale) >> 8, (c.getGreen() * scale) >> 8, 
                (c.getBlue() * scale) >> 8, c.getAlpha());
    }

    @Override
    public void buildClientConfiguration(JSONObject worldObject, DynmapWorld world, KzedMap map) {
        JSONObject o = new JSONObject();
        s(o, "type", "KzedMapType");
        s(o, "name", name);
        s(o, "title", title);
        s(o, "icon", icon);
        s(o, "prefix", prefix);
        s(o, "background", bg_cfg);
        s(o, "nightandday", night_and_day);
        s(o, "backgroundday", bg_day_cfg);
        s(o, "backgroundnight", bg_night_cfg);
        s(o, "bigmap", map.isBigWorldMap(world));
        s(o, "mapzoomin", mapzoomin);
        s(o, "mapzoomout", world.getExtraZoomOutLevels()+1);
        if(MapManager.mapman.getCompassMode() != CompassMode.PRE19)
            s(o, "compassview", "NE");   /* Always from northeast */
        else
            s(o, "compassview", "SE");   /* Always from southeast */
        s(o, "image-format", ImageFormat.FORMAT_PNG.getFileExt());
        a(worldObject, "maps", o);
    }
}
