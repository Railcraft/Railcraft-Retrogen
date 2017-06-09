/*
 *     Copyright © 2016 cpw
 *     This file is part of Simpleretrogen.
 *
 *     Simpleretrogen is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Simpleretrogen is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Simpleretrogen.  If not, see <http://www.gnu.org/licenses/>.
 */

package cpw.mods.retro;

import com.google.common.collect.*;
import com.google.common.collect.Sets.SetView;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkGenerator;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.common.registry.IForgeRegistryEntry;
import org.apache.logging.log4j.Level;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Mod(
        modid = "railcraftretrogen",
        name = "Railcraft Retrogen",
        acceptableRemoteVersions = "*",
        acceptedMinecraftVersions = "[1.10.2,1.11)",
        dependencies = "required-after:railcraft@[10.2.0-alpha-5,);"
)
@ParametersAreNonnullByDefault
public class WorldRetrogenRailcraft {
    private Multimap<String, String> markers = HashMultimap.create();
    private Map<String,TargetWorldWrapper> delegates;

    private Map<World,ListMultimap<ChunkPos,String>> pendingWork;
    private Map<World,ListMultimap<ChunkPos,String>> completedWork;

    private ConcurrentMap<World,Semaphore> completedWorkLocks;

    private int maxPerTick;
    private Map<String,String> retros = Maps.newHashMap();

    @EventHandler
    public void preInit(FMLPreInitializationEvent evt)
    {
        Configuration cfg = new Configuration(evt.getSuggestedConfigurationFile(), null, true);
        cfg.load();

        Property property = cfg.get(Configuration.CATEGORY_GENERAL, "maxPerTick", 100);
        property.setComment("Maximum number of retrogens to run in a single tick");
        this.maxPerTick = property.getInt(100);

        if (cfg.hasChanged())
        {
            cfg.save();
        }

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new LastTick());
        this.delegates = Maps.newHashMap();
    }

    @EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent evt)
    {
        this.pendingWork = new MapMaker().weakKeys().makeMap();
        this.completedWork = new MapMaker().weakKeys().makeMap();
        this.completedWorkLocks = new MapMaker().weakKeys().makeMap();
        this.markers.clear();

        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");
        Map<IWorldGenerator,Integer> worldGenIdx = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGeneratorIndex");

        for (Iterator<IWorldGenerator> iterator = worldGens.iterator(); iterator.hasNext(); )
        {
            IWorldGenerator wg = iterator.next();
            if (wg.getClass().getSimpleName().equals("GeneratorRailcraftOre"))
            {
                if (wg instanceof BooleanSupplier && wg instanceof Supplier && wg instanceof IForgeRegistryEntry)
                {

                    boolean retrogenEnabled = ((BooleanSupplier) wg).getAsBoolean();
                    String retrogenMarker = (String)((Supplier) wg).get();
                    ResourceLocation name = ((IForgeRegistryEntry) wg).getRegistryName();

                    if (retrogenEnabled && !delegates.containsKey(name.toString()))
                    {
                        FMLLog.info("Substituting worldgenerator %s with delegate", name);
                        iterator.remove();
                        TargetWorldWrapper tww = new TargetWorldWrapper();
                        tww.delegate = wg;
                        tww.tag = name.toString();
                        worldGens.add(tww);
                        Integer idx = worldGenIdx.remove(wg);
                        worldGenIdx.put(tww, idx);
                        FMLLog.info("Successfully substituted %s with delegate", name);
                        delegates.put(name.toString(), tww);
                        markers.put(retrogenMarker, name.toString());
                        retros.put(name.toString(), retrogenMarker);
                        break;
                    }
                }
            }
        }
    }

    @EventHandler
    public void serverStopped(FMLServerStoppedEvent evt)
    {
        Set<IWorldGenerator> worldGens = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGenerators");
        Map<IWorldGenerator,Integer> worldGenIdx = ObfuscationReflectionHelper.getPrivateValue(GameRegistry.class, null, "worldGeneratorIndex");

        for (TargetWorldWrapper tww : delegates.values())
        {
            worldGens.remove(tww);
            Integer idx = worldGenIdx.remove(tww);
            worldGens.add(tww.delegate);
            worldGenIdx.put(tww.delegate,idx);
        }

        delegates.clear();
    }

    private Semaphore getSemaphoreFor(World w)
    {
        completedWorkLocks.putIfAbsent(w, new Semaphore(1));
        return completedWorkLocks.get(w);
    }

    private class LastTick {
        private int counter = 0;
        @SubscribeEvent
        public void tickStart(TickEvent.WorldTickEvent tick)
        {
            World w = tick.world;
            if (!(w instanceof WorldServer))
            {
                return;
            }
            if (tick.phase == TickEvent.Phase.START)
            {
                counter = 0;
                getSemaphoreFor(w);
            }
            else
            {
                ListMultimap<ChunkPos, String> pending = pendingWork.get(w);
                if (pending == null)
                {
                    return;
                }
                ImmutableList<Entry<ChunkPos, String>> forProcessing = ImmutableList.copyOf(Iterables.limit(pending.entries(), maxPerTick + 1));
                for (Entry<ChunkPos, String> entry : forProcessing)
                {
                    if (counter++ > maxPerTick)
                    {
                        FMLLog.fine("Completed %d retrogens this tick. There are %d left for world %s", counter, pending.size(), w.getWorldInfo().getWorldName());
                        return;
                    }
                    runRetrogen((WorldServer)w, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private class TargetWorldWrapper implements IWorldGenerator {
        private IWorldGenerator delegate;
        private String tag;

        @Override
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
        {
            FMLLog.fine("Passing generation for %s through to underlying generator", tag);
            delegate.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
            ChunkPos chunkCoordIntPair = new ChunkPos(chunkX, chunkZ);
            completeRetrogen(chunkCoordIntPair, world, tag);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkDataEvent.Load chunkevt)
    {
        World w = chunkevt.getWorld();
        if (!(w instanceof WorldServer))
        {
            return;
        }
        getSemaphoreFor(w);

        Chunk chk = chunkevt.getChunk();
        Set<String> existingGens = Sets.newHashSet();
        NBTTagCompound data = chunkevt.getData();
        for (String m : markers.keySet())
        {
            NBTTagCompound marker = data.getCompoundTag(m);
            NBTTagList tagList = marker.getTagList("list", 8);
            for (int i = 0; i < tagList.tagCount(); i++)
            {
                existingGens.add(tagList.getStringTagAt(i));
            }

            SetView<String> difference = Sets.difference(new HashSet<>(markers.get(m)), existingGens);
            for (String retro : difference)
            {
                if (retros.containsKey(retro))
                {
                    queueRetrogen(retro, w, chk.getChunkCoordIntPair());
                }
            }
        }

        for (String retro : existingGens)
        {
            completeRetrogen(chk.getChunkCoordIntPair(), w, retro);
        }
    }

    @SubscribeEvent
    public void onChunkSave(ChunkDataEvent.Save chunkevt)
    {
        World w = chunkevt.getWorld();
        if (!(w instanceof WorldServer))
        {
            return;
        }
        getSemaphoreFor(w).acquireUninterruptibly();
        try
        {
            if (completedWork.containsKey(w))
            {
                ListMultimap<ChunkPos, String> doneChunks = completedWork.get(w);
                List<String> retroClassList = doneChunks.get(chunkevt.getChunk().getChunkCoordIntPair());
                if (retroClassList.isEmpty())
                    return;
                NBTTagCompound data = chunkevt.getData();
                for (String retroClass : retroClassList)
                {
                    String marker = retros.get(retroClass);
                    if (marker == null)
                    {
                        FMLLog.log(Level.DEBUG, "Encountered retrogen class %s with no existing marker, removing from chunk. You probably removed it from the active configuration", retroClass);
                        continue;
                    }
                    NBTTagList lst;
                    if (data.hasKey(marker)) {
                        lst = data.getCompoundTag(marker).getTagList("list", 8);
                    } else {
                        NBTTagCompound retro = new NBTTagCompound();
                        lst = new NBTTagList();
                        retro.setTag("list", lst);
                        data.setTag(marker, retro);
                    }
                    lst.appendTag(new NBTTagString(retroClass));
                }
            }
        }
        finally
        {
            getSemaphoreFor(w).release();
        }
    }

    private void queueRetrogen(String retro, World world, ChunkPos chunkCoords)
    {
        if (world instanceof WorldServer)
        {
            ListMultimap<ChunkPos, String> currentWork = pendingWork.computeIfAbsent(world, k -> ArrayListMultimap.create());

            currentWork.put(chunkCoords, retro);
        }
    }
    private void completeRetrogen(ChunkPos chunkCoords, World world, String retroClass)
    {
        ListMultimap<ChunkPos, String> pendingMap = pendingWork.get(world);
        if (pendingMap != null && pendingMap.containsKey(chunkCoords))
        {
            pendingMap.remove(chunkCoords, retroClass);
        }

        getSemaphoreFor(world).acquireUninterruptibly();
        try
        {
            ListMultimap<ChunkPos, String> completedMap = completedWork.computeIfAbsent(world, k -> ArrayListMultimap.create());

            completedMap.put(chunkCoords, retroClass);
        }
        finally
        {
            getSemaphoreFor(world).release();
        }
    }

    private void runRetrogen(WorldServer world, ChunkPos chunkCoords, String retroClass)
    {
        long worldSeed = world.getSeed();
        Random fmlRandom = new Random(worldSeed);
        long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        long chunkSeed = (xSeed * chunkCoords.chunkXPos + zSeed * chunkCoords.chunkZPos) ^ worldSeed;

        fmlRandom.setSeed(chunkSeed);
        ChunkProviderServer providerServer = world.getChunkProvider();
        IChunkGenerator generator = ObfuscationReflectionHelper.getPrivateValue(ChunkProviderServer.class, providerServer, "field_186029_c", "chunkGenerator");
        delegates.get(retroClass).delegate.generate(fmlRandom, chunkCoords.chunkXPos, chunkCoords.chunkZPos, world, generator, providerServer);
        FMLLog.fine("Retrogenerated chunk for %s", retroClass);
        completeRetrogen(chunkCoords, world, retroClass);
    }
}
