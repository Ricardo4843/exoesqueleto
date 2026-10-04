package exoesqueleto.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import exoesqueleto.armor.ArmorPiece;
import exoesqueleto.kinematics.ForwardKinematics3D;
import exoesqueleto.kinematics.HumanSkeleton;
import exoesqueleto.kinematics.Matrix4;
import exoesqueleto.kinematics.Node3D;
import exoesqueleto.kinematics.Segment;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Visor 3D del exoesqueleto (el equivalente a SkeletonVisualizer +
 * SkeletonPanel del lab2, pero con JavaFX en vez de Swing, porque Swing solo
 * dibuja en 2D).
 *
 * <h2>Conceptos básicos de JavaFX</h2>
 * <ul>
 * <li>Application: clase base de toda app JavaFX. launch() crea la ventana y
 * llama a start(Stage).</li>
 * <li>Stage: la ventana. Scene: su contenido.</li>
 * <li>Grafo de escena: todo lo que se dibuja es un árbol de nodos (Group,
 * botones, esferas...). Otro árbol, como el del exoesqueleto. Cada nodo puede
 * tener transformaciones (mover, girar) que también afectan a sus hijos: es
 * cinemática directa otra vez, hecha por JavaFX.</li>
 * <li>SubScene: una "ventana 3D" dentro de la interfaz 2D, con su propia
 * cámara y luces.</li>
 * <li>Hilo de JavaFX: toda la interfaz se toca desde un único hilo. Por eso el
 * bucle de animación (AnimationTimer) se ejecuta en él y no hay problemas de
 * concurrencia.</li>
 * </ul>
 *
 * Controles: arrastrar con el ratón para girar la cámara, rueda para el zoom.
 */
public class ExoskeletonApp extends Application {

	private static final String[] AXES = { "X", "Y", "Z" };

	// ---- Modelo ----
	private final Segment root = HumanSkeleton.build(); // árbol de segmentos
	private final List<Segment> segments = root.flatten(); // los mismos, en una lista
	private final Map<String, Segment> byName = new HashMap<>(); // búsqueda por nombre
	private Map<Segment, Matrix4> restInverse; // Frame_reposo^-1 de cada segmento (skinning)

	// ---- Vista 3D ----
	private final Group armorGroup = new Group(); // todas las piezas de armadura
	private final Group skeletonGroup = new Group(); // esferas (nodos) y cilindros (segmentos)
	private final List<ArmorPiece> pieces = new ArrayList<>();
	// Affine = transformación general de JavaFX (una matriz 3x4 como Matrix4).
	// Se guarda una por esfera y cilindro, y cada fotograma se le copia el frame
	// calculado por la cinemática.
	private final Map<Segment, Affine> jointTransforms = new IdentityHashMap<>();
	private final Map<Segment, Affine> boneTransforms = new IdentityHashMap<>();

	// ---- Cámara orbital ----
	// La cámara cuelga de un "brazo" que gira alrededor del cuerpo:
	// yaw = giro horizontal, pitch = inclinación arriba/abajo, zoom = distancia.
	private final Rotate yaw = new Rotate(200, Rotate.Y_AXIS);
	private final Rotate pitch = new Rotate(-12, Rotate.X_AXIS);
	private final Translate zoom = new Translate(0, 0, -420);
	private double dragX, dragY; // última posición del ratón al arrastrar

	// ---- Panel de controles ----
	private ComboBox<Segment> selector;
	private final Slider[] axisSliders = new Slider[3];
	private final Label[] axisLabels = new Label[3];
	private Slider detailSlider;
	private final Label vertexLabel = new Label();
	private final Label statsLabel = new Label();
	private ToggleButton walkButton;

	// ---- Estado del bucle ----
	// dirty = "la postura ha cambiado y hay que recalcular". Así no se repite
	// la cinemática y el skinning en cada fotograma si nada se ha movido.
	private boolean dirty = true;
	// Evita un bucle de eventos: al mover los sliders desde el código (por
	// ejemplo al cambiar de articulación) no se debe interpretar como si el
	// usuario hubiera movido la articulación.
	private boolean updatingSliders;
	private double walkTime; // segundos de animación de marcha acumulados
	private long lastFrame, fpsWindowStart; // marcas de tiempo en nanosegundos
	private int fpsFrames;
	private double fps;

	/** Punto de entrada de JavaFX: monta toda la escena y arranca el bucle. */
	@Override
	public void start(Stage stage) {
		for (Segment s : segments)
			byName.put(s.getName(), s);
		restInverse = computeRestInverse();
		// Parámetros de línea de comandos del tipo --detail=80 (opcional)
		int detail = Integer.parseInt(getParameters().getNamed().getOrDefault("detail", "56"));

		// Grupo "mundo": aquí van las coordenadas de la cinemática.
		Group world = new Group(armorGroup, skeletonGroup);
		// La cinemática usa Z hacia arriba (convenio de robótica), pero JavaFX usa
		// Y hacia ABAJO (convenio de pantallas: el píxel (0,0) está arriba a la
		// izquierda). Un giro de 90º sobre X convierte uno en otro:
		// (x, y, z) -> (x, -z, y). Como va en el grupo padre, afecta a todo lo de
		// dentro sin tocar el resto del código.
		world.getTransforms().add(new Rotate(90, Rotate.X_AXIS));
		buildSkeletonView();
		buildArmor(detail);

		// Suelo: una caja muy fina en y = 0
		Box floor = new Box(260, 1, 260);
		floor.setTranslateY(0.5);
		floor.setMaterial(new PhongMaterial(Color.web("#2b2f36")));

		// Iluminación "de tres puntos" simplificada: una luz principal cálida, una
		// de relleno fría desde el lado contrario y una ambiental para que las
		// sombras no sean negras del todo.
		PointLight key = new PointLight(Color.web("#fff6ea"));
		key.getTransforms().add(new Translate(-250, -350, -300));
		PointLight fill = new PointLight(Color.web("#6f86a8"));
		fill.getTransforms().add(new Translate(300, -150, 350));
		AmbientLight ambient = new AmbientLight(Color.web("#3a3f48"));

		// Cámara con perspectiva: lo lejano se ve más pequeño.
		// nearClip y farClip: solo se dibuja lo que está entre 1 y 5000 unidades
		// de la cámara. fieldOfView: ángulo de visión (35º, como un teleobjetivo
		// suave, deforma menos que uno ancho).
		PerspectiveCamera camera = new PerspectiveCamera(true);
		camera.setNearClip(1);
		camera.setFarClip(5000);
		camera.setFieldOfView(35);
		// Cadena de transformaciones de la cámara (cinemática otra vez): subir al
		// centro del cuerpo (y = -100, que es "arriba" en JavaFX), girar (yaw),
		// inclinar (pitch) y alejarse hacia atrás (zoom).
		Group cameraRig = new Group(camera);
		cameraRig.getTransforms().addAll(new Translate(0, -100, 0), yaw, pitch);
		camera.getTransforms().add(zoom);

		Group scene3d = new Group(world, floor, key, fill, ambient, cameraRig);
		// true = usar depth buffer (lo de delante tapa a lo de detrás).
		// BALANCED = antialiasing, para que los bordes no salgan en escalera.
		SubScene sub = new SubScene(scene3d, 900, 700, true, SceneAntialiasing.BALANCED);
		sub.setCamera(camera);
		sub.setFill(Color.web("#14171c"));
		// La SubScene no se redimensiona sola: se "enlaza" (bind) su tamaño al de
		// su contenedor. Con bind, cuando cambia uno, el otro se actualiza solo.
		Pane viewport = new Pane(sub);
		sub.widthProperty().bind(viewport.widthProperty());
		sub.heightProperty().bind(viewport.heightProperty());
		installCameraControls(sub);

		// BorderPane: la vista 3D en el centro y el panel a la izquierda
		BorderPane layout = new BorderPane(viewport);
		layout.setLeft(buildControls(detail));
		Scene scene = new Scene(layout, 1200, 760);
		stage.setTitle("Exoesqueleto 3D");
		stage.setScene(scene);
		stage.show();

		// AnimationTimer: JavaFX llama a handle() una vez por fotograma (unas 60
		// veces por segundo), con la hora actual en nanosegundos. Es una clase
		// anónima: se define y se instancia a la vez.
		new AnimationTimer() {
			@Override
			public void handle(long now) {
				frame(now);
			}
		}.start();

		String snapshot = getParameters().getNamed().get("snapshot");
		if (snapshot != null)
			takeSnapshotAndExit(scene, snapshot);
	}

	// ================================================================ postura

	/**
	 * Bucle principal, una vez por fotograma:
	 * 1. Si está caminando, avanza la animación.
	 * 2. Si la postura ha cambiado: cinemática directa -> matrices de skinning
	 *    -> armadura -> esqueleto.
	 * Mide el tiempo de cada fase con System.nanoTime(), como en el lab2.
	 */
	private void frame(long now) {
		if (lastFrame != 0 && walkButton.isSelected()) {
			// Tiempo real transcurrido desde el fotograma anterior. Así la marcha
			// va a la misma velocidad aunque el ordenador vaya a 30 o a 144 FPS.
			walkTime += (now - lastFrame) / 1e9;
			applyWalk(walkTime);
			refreshSliders();
			dirty = true;
		}
		lastFrame = now;

		// Fotogramas por segundo, medidos en ventanas de medio segundo
		fpsFrames++;
		if (now - fpsWindowStart > 500_000_000L) {
			fps = fpsFrames * 1e9 / (now - fpsWindowStart);
			fpsFrames = 0;
			fpsWindowStart = now;
		}
		if (!dirty)
			return;
		dirty = false;

		// 1) Cinemática directa (el algoritmo del lab2, en 3D)
		long t0 = System.nanoTime();
		Node3D tree = ForwardKinematics3D.computePositions(root, 0, 0, HumanSkeleton.PELVIS_HEIGHT);
		Map<Segment, Matrix4> frames = ForwardKinematics3D.frames(tree);
		long t1 = System.nanoTime();

		// 2) Matriz de skinning de cada hueso: S = Frame_actual * Frame_reposo^-1
		Map<Segment, double[]> skin = new IdentityHashMap<>();
		for (Segment s : segments)
			skin.put(s, frames.get(s).multiply(restInverse.get(s)).toArray());
		// 3) Mover los vértices de la armadura (solo si se está viendo)
		if (armorGroup.isVisible())
			for (ArmorPiece p : pieces)
				p.update(skin);
		long t2 = System.nanoTime();

		// 4) Colocar las esferas (en cada articulación) y los cilindros
		for (Segment s : segments) {
			Matrix4 f = frames.get(s);
			setAffine(jointTransforms.get(s), f);
			Affine bone = boneTransforms.get(s);
			if (bone != null)
				// Un Cylinder de JavaFX está centrado en su origen y orientado
				// según el eje Y. Para que cubra el segmento: avanzar media
				// longitud por Z y girar 90º sobre X (lleva el eje Y al Z).
				setAffine(bone, f.multiply(Matrix4.translation(0, 0, s.getLength() / 2))
						.multiply(Matrix4.rotX(Math.PI / 2)));
		}
		statsLabel.setText(String.format("Cinemática directa: %.1f µs%nSkinning armadura: %.2f ms%nFPS: %.0f",
				(t1 - t0) / 1e3, (t2 - t1) / 1e6, fps));
	}

	/**
	 * Ciclo de marcha muy simplificado: cada articulación oscila con un seno.
	 * No es biomecánica real (eso necesitaría datos de captura de movimiento o
	 * dinámica), pero basta para ver la cinemática en acción.
	 *
	 * p es la fase del ciclo (0,9 pasos por segundo). Las dos piernas van
	 * desfasadas medio ciclo (signos opuestos), y cada brazo va en oposición a
	 * la pierna de su lado, como al caminar de verdad.
	 */
	private void applyWalk(double t) {
		double p = 2 * Math.PI * 0.9 * t;
		double sin = Math.sin(p), cos = Math.cos(p);
		set("Muslo D", 0, 25 * sin); // cadera: +-25º adelante/atrás
		set("Muslo I", 0, -25 * sin);
		// Rodilla: se dobla sobre todo cuando la pierna pasa por delante (fase de
		// balanceo). Math.max(0, cos) recorta la mitad negativa del coseno.
		set("Tibia D", 0, -(5 + 55 * Math.max(0, cos)));
		set("Tibia I", 0, -(5 + 55 * Math.max(0, -cos)));
		set("Pie D", 0, 10 * sin);
		set("Pie I", 0, -10 * sin);
		set("Brazo D", 0, -22 * sin); // brazos en oposición a las piernas
		set("Brazo I", 0, 22 * sin);
		set("Antebrazo D", 0, 20 + 15 * Math.max(0, -sin)); // codos algo doblados
		set("Antebrazo I", 0, 20 + 15 * Math.max(0, sin));
		set("Tórax", 2, -6 * sin); // los hombros giran al contrario que la pelvis
		set("Pelvis", 2, 4 * sin);
	}

	/** Atajo: pone un ángulo en grados a un segmento buscado por nombre. */
	private void set(String name, int axis, double degrees) {
		byName.get(name).setAngle(axis, Math.toRadians(degrees));
	}

	/**
	 * Calcula Frame_reposo^-1 de cada segmento: la cinemática directa con
	 * todos los ángulos a 0, invertida. Se guardan los ángulos actuales, se
	 * ponen a 0, se calcula y se restauran.
	 */
	private Map<Segment, Matrix4> computeRestInverse() {
		Map<Segment, double[]> saved = new IdentityHashMap<>();
		for (Segment s : segments) {
			saved.put(s, new double[] { s.getAngle(0), s.getAngle(1), s.getAngle(2) });
			s.resetAngles();
		}
		Map<Segment, Matrix4> rest = ForwardKinematics3D
				.frames(ForwardKinematics3D.computePositions(root, 0, 0, HumanSkeleton.PELVIS_HEIGHT));
		Map<Segment, Matrix4> inverse = new IdentityHashMap<>();
		for (Segment s : segments) {
			inverse.put(s, rest.get(s).rigidInverse());
			for (int i = 0; i < 3; i++)
				s.setAngle(i, saved.get(s)[i]);
		}
		return inverse;
	}

	// ================================================================ vista 3D

	/**
	 * (Re)genera todas las piezas de armadura con la resolución dada. Se llama
	 * al arrancar y cada vez que se mueve el slider de detalle.
	 *
	 * @param sides Vértices por anillo (y número de anillos): sides² por pieza.
	 */
	private void buildArmor(int sides) {
		// Frame_reposo = inversa de la inversa (la inversa de una inversa es la
		// matriz original)
		Map<Segment, Matrix4> rest = new IdentityHashMap<>();
		for (Segment s : segments)
			rest.put(s, restInverse.get(s).rigidInverse());

		// PhongMaterial: modelo de iluminación de Phong = color difuso (el color
		// "de verdad") + brillo especular (el reflejo blanco que lo hace parecer
		// metal). specularPower alto = reflejo pequeño y concentrado.
		PhongMaterial plateA = metal("#4a5a6e");
		PhongMaterial plateB = metal("#2f3a48");
		PhongMaterial accent = metal("#b08d3c");

		pieces.clear();
		armorGroup.getChildren().clear();
		int total = 0;
		for (Segment s : segments) {
			if (s.getShape() == Segment.Shape.NONE)
				continue; // sin armadura (pelvis, caderas, clavículas)
			ArmorPiece piece = new ArmorPiece(s, sides, sides, rest);
			pieces.add(piece);
			total += piece.getVertexCount();
			// MeshView: el nodo que dibuja una malla (TriangleMesh) en la escena
			MeshView view = new MeshView(piece.getMesh());
			// Colores: tubos alternando dos tonos (como placas) y elipsoides
			// (cabeza, manos y pies) en dorado
			boolean lower = s.getName().startsWith("Tibia") || s.getName().startsWith("Antebrazo")
					|| s.getName().equals("Cuello");
			view.setMaterial(s.getShape() == Segment.Shape.ELLIPSOID ? accent : lower ? plateB : plateA);
			armorGroup.getChildren().add(view);
		}
		vertexLabel.setText(String.format("%,d vértices · %d piezas", total, pieces.size()));
		dirty = true;
	}

	/**
	 * Crea la vista del esqueleto "desnudo", como el dibujo del lab2 pero en 3D:
	 * una esfera en cada articulación (los nodos) y un cilindro por segmento.
	 * Empieza oculta (se activa con la casilla "Esqueleto").
	 */
	private void buildSkeletonView() {
		PhongMaterial jointMat = new PhongMaterial(Color.web("#e8553f"));
		PhongMaterial boneMat = new PhongMaterial(Color.web("#f2c14e"));
		for (Segment s : segments) {
			Sphere joint = new Sphere(2.4);
			joint.setMaterial(jointMat);
			Affine ja = new Affine();
			joint.getTransforms().add(ja);
			jointTransforms.put(s, ja);
			skeletonGroup.getChildren().add(joint);
			if (s.getLength() > 0) { // la pelvis mide 0: no lleva cilindro
				Cylinder bone = new Cylinder(1.1, s.getLength());
				bone.setMaterial(boneMat);
				Affine ba = new Affine();
				bone.getTransforms().add(ba);
				boneTransforms.put(s, ba);
				skeletonGroup.getChildren().add(bone);
			}
		}
		skeletonGroup.setVisible(false);
	}

	/** Material metálico: color difuso dado y reflejo especular blanco azulado. */
	private static PhongMaterial metal(String color) {
		PhongMaterial m = new PhongMaterial(Color.web(color));
		m.setSpecularColor(Color.web("#d9e2ec"));
		m.setSpecularPower(28);
		return m;
	}

	/** Copia una Matrix4 en un Affine de JavaFX (los dos son matrices 3x4 por filas). */
	private static void setAffine(Affine a, Matrix4 f) {
		double[] m = f.toArray();
		a.setToTransform(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
	}

	/**
	 * Cámara orbital con el ratón. Se usan lambdas (e -> {...}): funciones
	 * anónimas que JavaFX ejecuta cuando ocurre el evento.
	 */
	private void installCameraControls(SubScene sub) {
		sub.setOnMousePressed(e -> {
			dragX = e.getSceneX();
			dragY = e.getSceneY();
		});
		sub.setOnMouseDragged(e -> {
			// El desplazamiento del ratón (en píxeles) se convierte en grados
			yaw.setAngle(yaw.getAngle() + (e.getSceneX() - dragX) * 0.4);
			// La inclinación se limita a +-80º para no dar la vuelta por encima
			pitch.setAngle(Math.max(-80, Math.min(80, pitch.getAngle() - (e.getSceneY() - dragY) * 0.4)));
			dragX = e.getSceneX();
			dragY = e.getSceneY();
		});
		// Rueda: acercar o alejar, entre 120 y 1500 unidades del centro
		sub.setOnScroll(e -> zoom.setZ(Math.max(-1500, Math.min(-120, zoom.getZ() + e.getDeltaY()))));
	}

	// ================================================================ panel

	/** Construye el panel de la izquierda con todos los controles. */
	private VBox buildControls(int detail) {
		Label title = new Label("Exoesqueleto 3D");
		title.setStyle("-fx-font-size: 18; -fx-font-weight: bold;"); // CSS de JavaFX

		// Desplegable con los segmentos que tienen al menos un eje libre
		selector = new ComboBox<>();
		for (Segment s : segments)
			if (s.isAxisFree(0) || s.isAxisFree(1) || s.isAxisFree(2))
				selector.getItems().add(s);
		selector.setMaxWidth(Double.MAX_VALUE);
		selector.setOnAction(e -> refreshSliders());

		// VBox: coloca sus hijos en columna, con 8 px de separación
		VBox box = new VBox(8, title, new Label("Articulación"), selector);

		// Un slider por eje (X, Y, Z)
		for (int i = 0; i < 3; i++) {
			// Copia "final" de i: una lambda solo puede usar variables locales que
			// no cambian, y la i del for cambia en cada vuelta.
			int axis = i;
			axisLabels[i] = new Label();
			axisSliders[i] = new Slider();
			// Listener: se ejecuta cada vez que cambia el valor del slider
			axisSliders[i].valueProperty().addListener((obs, old, val) -> {
				Segment s = selector.getValue();
				if (updatingSliders || s == null)
					return; // el cambio viene del propio código, no del usuario
				s.setAngle(axis, Math.toRadians(val.doubleValue()));
				updateAxisLabel(axis, s);
				dirty = true; // la postura ha cambiado: recalcular en el próximo fotograma
			});
			box.getChildren().addAll(axisLabels[i], axisSliders[i]);
		}

		Button reset = new Button("Postura de reposo");
		reset.setOnAction(e -> {
			walkButton.setSelected(false);
			// Referencia a método: equivale a s -> s.resetAngles()
			segments.forEach(Segment::resetAngles);
			refreshSliders();
			dirty = true;
		});
		// ToggleButton: botón que se queda pulsado o no (on/off)
		walkButton = new ToggleButton("Caminar");
		walkButton.selectedProperty().addListener((obs, old, on) -> {
			// Mientras camina, los sliders solo muestran (no se pueden mover)
			for (Slider s : axisSliders)
				s.setDisable(on || s.isDisable());
			if (!on)
				refreshSliders();
		});

		CheckBox showArmor = new CheckBox("Armadura");
		showArmor.setSelected(true);
		showArmor.selectedProperty().addListener((obs, old, on) -> {
			armorGroup.setVisible(on);
			dirty = true; // al volver a mostrarla hay que ponerla en la postura actual
		});
		CheckBox showSkeleton = new CheckBox("Esqueleto (segmentos y nodos)");
		showSkeleton.selectedProperty().addListener((obs, old, on) -> skeletonGroup.setVisible(on));

		// Slider de detalle: de 8 a 160 vértices por anillo, en saltos de 8
		detailSlider = new Slider(8, 160, detail);
		detailSlider.setMajorTickUnit(8);
		detailSlider.setSnapToTicks(true);
		// Solo se regenera al SOLTAR el slider (valueChanging pasa a false): si se
		// regenerase mientras se arrastra, se crearían cientos de mallas para nada
		detailSlider.valueChangingProperty().addListener((obs, was, changing) -> {
			if (!changing)
				buildArmor((int) detailSlider.getValue());
		});

		box.getChildren().addAll(new Separator(), reset, walkButton, showArmor, showSkeleton, new Separator(),
				new Label("Detalle de la armadura"), detailSlider, vertexLabel, new Separator(), statsLabel);
		box.setPadding(new Insets(14));
		box.setPrefWidth(270);

		selector.getSelectionModel().select(byName.get("Brazo D"));
		refreshSliders();
		return box;
	}

	/**
	 * Ajusta los 3 sliders a la articulación seleccionada: rango = límites del
	 * eje, valor = ángulo actual, deshabilitado si el eje está bloqueado.
	 */
	private void refreshSliders() {
		Segment s = selector.getValue();
		if (s == null)
			return;
		updatingSliders = true; // que el listener ignore estos cambios
		for (int i = 0; i < 3; i++) {
			Slider sl = axisSliders[i];
			boolean free = s.isAxisFree(i);
			sl.setMin(Math.toDegrees(s.getMin(i)));
			sl.setMax(Math.toDegrees(s.getMax(i)));
			sl.setValue(Math.toDegrees(s.getAngle(i)));
			sl.setDisable(!free || walkButton != null && walkButton.isSelected());
			updateAxisLabel(i, s);
		}
		updatingSliders = false;
	}

	/** Texto encima de cada slider, por ejemplo "Flexión (eje X): 35°". */
	private void updateAxisLabel(int axis, Segment s) {
		axisLabels[axis].setText(s.isAxisFree(axis)
				? String.format("%s (eje %s): %.0f°", s.getAxisName(axis), AXES[axis], Math.toDegrees(s.getAngle(axis)))
				: "Eje " + AXES[axis] + ": bloqueado");
	}

	// ================================================================ captura

	/**
	 * Modo captura, para generar imágenes sin tocar el ratón:
	 * --snapshot=fichero.png [--walk=segundos] [--yaw=grados] [--skeleton=1].
	 * Espera 1,5 s a que se dibuje la escena, la guarda en PNG y cierra.
	 */
	private void takeSnapshotAndExit(Scene scene, String file) {
		String walk = getParameters().getNamed().get("walk");
		if (walk != null) {
			applyWalk(Double.parseDouble(walk));
			refreshSliders();
			dirty = true;
		}
		String yawArg = getParameters().getNamed().get("yaw");
		if (yawArg != null)
			yaw.setAngle(Double.parseDouble(yawArg));
		String skel = getParameters().getNamed().get("skeleton");
		if (skel != null) {
			skeletonGroup.setVisible(true);
			armorGroup.setVisible(false);
		}
		// PauseTransition: ejecuta algo pasado un tiempo, sin bloquear el hilo de
		// JavaFX (un Thread.sleep congelaría la ventana y no se dibujaría nada)
		PauseTransition wait = new PauseTransition(Duration.seconds(1.5));
		wait.setOnFinished(e -> {
			WritableImage img = scene.snapshot(null);
			// Se pasa la imagen de JavaFX a una de AWT (BufferedImage) píxel a
			// píxel, porque ImageIO, que es quien sabe escribir PNG, es de AWT
			int w = (int) img.getWidth(), h = (int) img.getHeight();
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			PixelReader pr = img.getPixelReader();
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++)
					out.setRGB(x, y, pr.getArgb(x, y));
			try {
				ImageIO.write(out, "png", new File(file));
				System.out.println(statsLabel.getText().replace('\n', ' ') + " | " + vertexLabel.getText());
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			Platform.exit();
		});
		wait.play();
	}

	/** main: delega en launch(), que inicia JavaFX y acaba llamando a start(). */
	public static void main(String[] args) {
		launch(args);
	}
}
