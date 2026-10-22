package com.survivalcinema.client.flashback;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.mixin.flashback.ExportDoneWindowInvoker;
import com.survivalcinema.client.mixin.flashback.RecorderAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flashback has no API: we call its internals. {@code fabric.mod.json} only asks for 0.39.10 or newer,
 * but even a small update can rename something. So at startup the mod reads its own bytecode, finds every Flashback
 * class, method and field it uses, and checks they are all there, and that our mixins into Flashback were applied
 * (they are optional, a failed one does not crash the game). When something is missing, recording and filming stay
 * off and the player is told to update Survival Cinema, instead of the game crashing in the middle of a film.
 */
public final class FlashbackCheck {
	private static final String FLASHBACK_PACKAGE = "com/moulberry/flashback/";
	/**
	 * Our classes that touch Flashback (their nested classes and lambdas are read with them). Names, not classes: a mixin
	 * class must never be loaded as a class.
	 */
	private static final List<String> USERS = List.of("com/survivalcinema/client/flashback/FlashbackBridge",
		"com/survivalcinema/client/edit/EncoderSelector", "com/survivalcinema/client/mixin/flashback/ExportJobMixin",
		"com/survivalcinema/client/mixin/flashback/ReplayUIMixin");
	/** Flashback classes our mixins change, and what each mixin adds to them. */
	private static final List<String[]> MIXINS = List.of(
		new String[]{"com.moulberry.flashback.record.Recorder", RecorderAccessor.class.getName()},
		new String[]{"com.moulberry.flashback.editor.ui.windows.ExportDoneWindow", ExportDoneWindowInvoker.class.getName()},
		new String[]{"com.moulberry.flashback.exporting.ExportJob", FlashbackHooked.class.getName()},
		new String[]{"com.moulberry.flashback.editor.ui.ReplayUI", FlashbackHooked.class.getName()});

	/** What the check found: Flashback's version and what is missing (empty when everything is there). */
	public record Result(String version, List<String> missing) {
		public boolean ok() {
			return missing.isEmpty();
		}
	}

	private static volatile @Nullable Result result;

	private FlashbackCheck() {
	}

	/** The check's result, running it the first time. Client thread (it loads Flashback's classes). */
	public static Result result() {
		Result known = result;
		if (known == null) {
			known = run();
			result = known;
		}
		return known;
	}

	/** Why recording and filming are off, or null when Flashback is fine. */
	public static @Nullable Component problem() {
		Result checked = result();
		return checked.ok() ? null : Component.translatable("survivalcinema.flashback.unsupported", checked.version());
	}

	private static Result run() {
		String version = FabricLoader.getInstance().getModContainer("flashback")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		List<String> missing = new ArrayList<>();
		ClassLoader loader = FlashbackCheck.class.getClassLoader();
		try {
			List<String[]> mixins = new ArrayList<>(MIXINS);
			if (FabricLoader.getInstance().isModLoaded("voicechat")) {
				// Flashback's voice chat playback exists only with Simple Voice Chat installed
				mixins.add(new String[]{"com.moulberry.flashback.compat.simple_voice_chat.SimpleVoiceChatPlayback", FlashbackHooked.class.getName()});
			}
			for (String[] mixin : mixins) {
				Class<?> target = load(mixin[0], loader);
				Class<?> added = Class.forName(mixin[1], false, loader);
				if (target == null) {
					missing.add(mixin[0]);
				} else if (!added.isAssignableFrom(target)) {
					missing.add("mixin " + mixin[1] + " into " + mixin[0]);
				}
			}
			for (String reference : references(loader)) {
				if (!resolves(reference, loader)) {
					missing.add(reference);
				}
			}
		} catch (Throwable t) {
			SurvivalCinemaClient.LOGGER.error("Could not check Flashback {}", version, t);
			missing.add("check failed: " + t);
		}
		if (missing.isEmpty()) {
			SurvivalCinemaClient.LOGGER.info("Flashback {}: everything Survival Cinema uses is there", version);
		} else {
			SurvivalCinemaClient.LOGGER.error("Flashback {} is not supported by this Survival Cinema, recording and filming are off. Missing: {}",
				version, missing);
		}
		return new Result(version, List.copyOf(missing));
	}

	/** "owner#name descriptor" of every Flashback member our classes use, and "owner" of every class. */
	static Set<String> references(ClassLoader loader) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		Deque<String> classes = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		classes.addAll(USERS);
		if (FabricLoader.getInstance().isModLoaded("voicechat")) {
			classes.add("com/survivalcinema/client/mixin/flashback/VoiceChatPlaybackMixin");
		}
		while (!classes.isEmpty()) {
			String name = classes.poll();
			if (!seen.add(name)) {
				continue;
			}
			ClassNode node = read(name, loader);
			if (node == null) {
				continue;
			}
			for (InnerClassNode inner : node.innerClasses) {
				if (inner.name.startsWith(name + "$")) {
					classes.add(inner.name);
				}
			}
			for (MethodNode method : node.methods) {
				addTypes(Type.getMethodType(method.desc), found);
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call) {
						addMember(call.owner, call.name, call.desc, found);
					} else if (insn instanceof FieldInsnNode field) {
						addMember(field.owner, field.name, field.desc, found);
					} else if (insn instanceof TypeInsnNode type) {
						addType(Type.getObjectType(type.desc), found);
					} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
						addType(type, found);
					} else if (insn instanceof InvokeDynamicInsnNode indy) {
						for (Object arg : indy.bsmArgs) {
							if (arg instanceof Handle handle) {
								addMember(handle.getOwner(), handle.getName(), handle.getDesc(), found);
							}
						}
					}
				}
			}
		}
		return found;
	}

	private static @Nullable ClassNode read(String internalName, ClassLoader loader) throws IOException {
		try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
			if (in == null) {
				return null;
			}
			ClassNode node = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
			return node;
		}
	}

	private static void addMember(String owner, String name, String desc, Set<String> found) {
		if (owner.startsWith(FLASHBACK_PACKAGE)) {
			found.add(owner + "#" + name + " " + desc);
		}
		addTypes(desc.startsWith("(") ? Type.getMethodType(desc) : Type.getType(desc), found);
	}

	private static void addTypes(Type type, Set<String> found) {
		if (type.getSort() == Type.METHOD) {
			addType(type.getReturnType(), found);
			for (Type argument : type.getArgumentTypes()) {
				addType(argument, found);
			}
		} else {
			addType(type, found);
		}
	}

	private static void addType(Type type, Set<String> found) {
		Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
		if (element.getSort() == Type.OBJECT && element.getInternalName().startsWith(FLASHBACK_PACKAGE)) {
			found.add(element.getInternalName());
		}
	}

	static boolean resolves(String reference, ClassLoader loader) {
		int hash = reference.indexOf('#');
		Class<?> owner = load((hash < 0 ? reference : reference.substring(0, hash)).replace('/', '.'), loader);
		if (owner == null || hash < 0) {
			return owner != null;
		}
		String member = reference.substring(hash + 1);
		int space = member.indexOf(' ');
		String name = member.substring(0, space);
		String desc = member.substring(space + 1);
		try {
			return desc.startsWith("(") ? hasMethod(owner, name, desc) : hasField(owner, name, desc);
		} catch (LinkageError e) {
			return false; // a type in the signature is missing
		}
	}

	private static boolean hasMethod(Class<?> owner, String name, String desc) {
		if (name.equals("<init>")) {
			for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
				if (Type.getConstructorDescriptor(constructor).equals(desc)) {
					return true;
				}
			}
			return false;
		}
		Deque<Class<?>> todo = new ArrayDeque<>();
		todo.add(owner);
		Set<Class<?>> seen = new HashSet<>();
		while (!todo.isEmpty()) {
			Class<?> type = todo.poll();
			if (type == null || !seen.add(type)) {
				continue;
			}
			for (Method method : type.getDeclaredMethods()) {
				if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) {
					return true;
				}
			}
			todo.add(type.getSuperclass());
			todo.addAll(List.of(type.getInterfaces()));
		}
		return false;
	}

	private static boolean hasField(Class<?> owner, String name, String desc) {
		for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (field.getName().equals(name) && Type.getDescriptor(field.getType()).equals(desc)) {
					return true;
				}
			}
			for (Class<?> face : type.getInterfaces()) {
				for (Field field : face.getFields()) {
					if (field.getName().equals(name) && Type.getDescriptor(field.getType()).equals(desc)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static @Nullable Class<?> load(String name, ClassLoader loader) {
		try {
			return Class.forName(name, false, loader);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
