package exoesqueleto.armor;

import java.util.Arrays;
import java.util.Map;

import exoesqueleto.kinematics.Matrix4;
import exoesqueleto.kinematics.Segment;
import javafx.scene.shape.TriangleMesh;

/**
 * Pieza de armadura que cubre un segmento: una malla de miles de triángulos
 * que se deforma con la postura mediante SKINNING.
 *
 * <h2>La idea clave del proyecto</h2>
 * Los miles de vértices de la armadura NO son nodos del árbol cinemático.
 * Meterlos en el árbol sería absurdo: un vértice no es una articulación. Lo
 * que se hace en videojuegos y software de animación (y aquí) es:
 * <ol>
 * <li>Calcular la cinemática directa solo para los ~25 segmentos (unos
 * microsegundos).</li>
 * <li>Mover cada vértice con la matriz del hueso al que está "pegado".</li>
 * </ol>
 *
 * <h2>La fórmula</h2>
 * Cada vértice se guarda en su posición de REPOSO, en coordenadas del mundo.
 * Para llevarlo a la postura actual:
 *
 * <pre>
 * v_actual = Frame_actual * Frame_reposo^-1 * v_reposo
 *            \__________ S ______________/
 * </pre>
 *
 * Leído de derecha a izquierda: Frame_reposo^-1 lleva el vértice del mundo a
 * coordenadas LOCALES del hueso ("a 3 cm a la derecha y 10 cm a lo largo del
 * muslo"), y Frame_actual lo devuelve al mundo, pero con el hueso ya movido.
 * S (matriz de skinning) se calcula una vez por hueso y por fotograma, y luego
 * se aplica a todos sus vértices.
 *
 * <h2>Linear Blend Skinning (LBS)</h2>
 * Si cada vértice siguiera solo a su hueso, al doblar la rodilla se abriría
 * una grieta entre el muslo y la tibia. Para evitarlo, cerca de la
 * articulación de inicio cada vértice mezcla su hueso con el del padre:
 *
 * <pre>
 * v = w * S_propio * v_reposo + (1 - w) * S_padre * v_reposo
 * </pre>
 *
 * con un peso w que va de 0,5 en la articulación a 1 a unos cm de distancia.
 * Es una media ponderada de las dos posiciones posibles.
 *
 * <h2>Malla de triángulos</h2>
 * Las gráficas 3D dibujan todo con triángulos. Una malla tiene dos listas:
 * <ul>
 * <li>points: coordenadas de los vértices (x0, y0, z0, x1, y1, z1...).</li>
 * <li>faces: cada triángulo como 3 índices de vértice (qué 3 puntos une).</li>
 * </ul>
 * Los vértices compartidos por varios triángulos se guardan una sola vez. Al
 * mover un vértice se mueven todos los triángulos que lo usan.
 */
public class ArmorPiece {

	/** Longitud (cm) de la zona de mezcla con el hueso padre. */
	private static final double BLEND_LENGTH = 7;

	private final Segment segment;
	private final int vertexCount;
	// Posiciones de reposo (mundo): 3 doubles por vértice, todos seguidos en
	// un array plano. Es mucho más rápido que un array de objetos Punto: los
	// datos quedan juntos en memoria y no hay que crear 50.000 objetos.
	private final double[] rest;
	private final double[] weight; // peso w del hueso propio (1 - w va al padre)
	private final float[] points; // posiciones actuales, en el formato de JavaFX (float)
	private final TriangleMesh mesh = new TriangleMesh();

	/**
	 * Genera la malla de la pieza.
	 *
	 * La forma es un tubo deformado: "rings" anillos a lo largo del segmento,
	 * cada uno con "sides" vértices alrededor, más 2 vértices "polo" que tapan
	 * los extremos. Total: sides * rings + 2 vértices.
	 *
	 * @param seg        Segmento que cubre la pieza.
	 * @param sides      Vértices por anillo (resolución alrededor).
	 * @param rings      Anillos a lo largo del segmento (resolución a lo largo).
	 * @param restFrames Frame de cada segmento en la postura de reposo.
	 */
	public ArmorPiece(Segment seg, int sides, int rings, Map<Segment, Matrix4> restFrames) {
		this.segment = seg;
		this.vertexCount = sides * rings + 2;
		this.rest = new double[vertexCount * 3];
		this.weight = new double[vertexCount];
		this.points = new float[vertexCount * 3];

		// Tramo del eje Z local que cubre la pieza: de "start" a "end"
		double start = seg.getShape() == Segment.Shape.ELLIPSOID ? 0 : seg.getArmorStart();
		double end = seg.getLength();
		// En segmentos cortos (cuello, 10 cm) la zona de mezcla no puede medir 7 cm
		double blend = Math.min(BLEND_LENGTH, 0.3 * seg.getLength());
		double[] f = restFrames.get(seg).toArray();
		boolean hasParent = seg.getParent() != null; // la raíz no tiene con quién mezclar

		int v = 0; // índice del siguiente vértice a rellenar
		for (int r = 0; r < rings; r++) {
			// u = posición a lo largo de la pieza normalizada a [0, 1].
			// scale = factor por el que se multiplica el radio en este anillo.
			double u, scale;
			if (seg.getShape() == Segment.Shape.ELLIPSOID) {
				// Elipsoide: el radio va como sqrt(sin(pi*u)): 0 en las puntas y
				// máximo en el centro. La raíz cuadrada redondea las puntas (con
				// solo sin(pi*u) saldría un huso puntiagudo). El +0,5 evita los
				// anillos de radio 0 exacto (ya están los polos).
				u = (r + 0.5) / rings;
				scale = Math.sqrt(Math.sin(Math.PI * u));
			} else {
				// Tubo: u va de 0 a 1 incluidos. Un abombamiento del 6% en el
				// centro (sin(pi*u)) y un reborde del 4% en los extremos le dan
				// aspecto de placa de armadura en vez de tubería.
				u = r / (rings - 1.0);
				scale = 1 + 0.06 * Math.sin(Math.PI * u) + (u < 0.04 || u > 0.96 ? 0.04 : 0);
			}
			double t = start + u * (end - start); // altura del anillo en cm (Z local)
			double k = (t - start) / (end - start); // 0 al inicio, 1 al final
			// Interpolación lineal (lerp) entre el radio inicial y el final: así
			// la pieza se estrecha de forma progresiva (muslo, antebrazo...)
			double rx = scale * lerp(seg.getRadiusXStart(), seg.getRadiusXEnd(), k);
			double ry = scale * lerp(seg.getRadiusYStart(), seg.getRadiusYEnd(), k);
			// Peso: 0,5 en la articulación (t = 0, mitad y mitad con el padre),
			// sube a 1 en t = blend. Si t es negativo (armorStart), baja hasta 0
			// (todo del padre). clamp lo mantiene en [0, 1].
			double w = hasParent ? clamp(0.5 + 0.5 * t / blend) : 1;

			// Vértices del anillo: elipse en coordenadas polares,
			// (rx*cos(a), ry*sin(a)) con a de 0 a 2*pi.
			for (int s = 0; s < sides; s++) {
				double a = 2 * Math.PI * s / sides;
				setRest(v++, f, rx * Math.cos(a), ry * Math.sin(a), t, w);
			}
		}
		// Los dos polos (en el eje del segmento) que cierran los extremos
		setRest(v++, f, 0, 0, start, hasParent ? clamp(0.5 + 0.5 * start / blend) : 1);
		setRest(v, f, 0, 0, end, 1);

		buildFaces(sides, rings);
		// JavaFX exige coordenadas de textura aunque no se use textura: se pone
		// una sola (0, 0) y todas las caras la comparten.
		mesh.getTexCoords().addAll(0, 0);
	}

	/**
	 * Guarda un vértice en su posición de reposo. Recibe coordenadas LOCALES
	 * del hueso (x, y, z) y las pasa al mundo multiplicando por el frame de
	 * reposo: es la multiplicación matriz 4x4 * (x, y, z, 1) escrita a mano.
	 */
	private void setRest(int i, double[] f, double x, double y, double z, double w) {
		rest[i * 3] = f[0] * x + f[1] * y + f[2] * z + f[3];
		rest[i * 3 + 1] = f[4] * x + f[5] * y + f[6] * z + f[7];
		rest[i * 3 + 2] = f[8] * x + f[9] * y + f[10] * z + f[11];
		weight[i] = w;
	}

	/**
	 * Construye los triángulos (la "topología" de la malla). Solo depende de
	 * cuántos vértices hay, no de dónde están, así que se hace una vez. Al
	 * moverse la armadura solo cambian los points.
	 */
	private void buildFaces(int sides, int rings) {
		int startPole = sides * rings, endPole = startPole + 1; // índices de los polos
		// Entre cada 2 anillos consecutivos hay "sides" cuadriláteros, de 2
		// triángulos cada uno. Más 1 triángulo por lado en cada tapa.
		int faceCount = 2 * sides * (rings - 1) + 2 * sides;
		// Cada cara ocupa 6 enteros en JavaFX: (vértice, textura) x 3
		int[] faces = new int[faceCount * 6];
		int i = 0;
		for (int r = 0; r < rings - 1; r++)
			for (int s = 0; s < sides; s++) {
				// Las 4 esquinas del cuadrilátero:
				// c ---- d      (anillo r+1)
				// |  \   |
				// a ---- b      (anillo r)
				// "% sides" hace que el último vértice del anillo se una con el
				// primero (el tubo se cierra).
				int a = r * sides + s, b = r * sides + (s + 1) % sides;
				int c = a + sides, d = b + sides;
				i = face(faces, i, a, b, c);
				i = face(faces, i, b, d, c);
			}
		// Tapas: abanico de triángulos desde cada polo hasta su anillo.
		// El orden de los vértices (sentido de giro) decide hacia qué lado mira
		// el triángulo, y por eso las dos tapas lo tienen invertido.
		int last = (rings - 1) * sides;
		for (int s = 0; s < sides; s++) {
			int s2 = (s + 1) % sides;
			i = face(faces, i, startPole, s2, s);
			i = face(faces, i, last + s, last + s2, endPole);
		}
		mesh.getFaces().addAll(faces);

		// Grupos de suavizado: las caras del mismo grupo comparten normales en
		// sus vértices, y JavaFX interpola la luz entre ellas: la superficie se
		// ve curva y lisa. Sin esto se verían todas las facetas planas.
		int[] smoothing = new int[faceCount];
		Arrays.fill(smoothing, 1);
		mesh.getFaceSmoothingGroups().addAll(smoothing);
		// Hueco para los puntos: se rellena de verdad en update()
		mesh.getPoints().addAll(points);
	}

	/** Escribe un triángulo en el array de caras y devuelve la siguiente posición libre. */
	private static int face(int[] faces, int i, int p0, int p1, int p2) {
		faces[i] = p0;
		faces[i + 2] = p1;
		faces[i + 4] = p2;
		return i + 6; // los índices de textura (posiciones impares) se quedan a 0
	}

	/**
	 * Recoloca todos los vértices según la postura actual (linear blend
	 * skinning, ver el javadoc de la clase). Se llama en cada fotograma en que
	 * cambia la postura: es la parte cara del programa, O(vértices).
	 *
	 * @param skin Matriz S = Frame_actual * Frame_reposo^-1 de cada segmento,
	 *             ya convertida a array de 12 doubles.
	 */
	public void update(Map<Segment, double[]> skin) {
		double[] a = skin.get(segment); // S del hueso propio
		// S del padre (si no hay padre se usa la propia: la mezcla no cambia nada)
		double[] b = segment.getParent() != null ? skin.get(segment.getParent()) : a;
		for (int i = 0; i < vertexCount; i++) {
			double x = rest[i * 3], y = rest[i * 3 + 1], z = rest[i * 3 + 2];
			double w = weight[i], w2 = 1 - w;
			// Cada coordenada: w * (fila de A · vértice) + (1 - w) * (fila de B · vértice).
			// Escrito a mano en vez de con Matrix4.multiply para no crear
			// objetos nuevos por cada vértice (50.000 por fotograma serían
			// mucho trabajo para el recolector de basura).
			points[i * 3] = (float) (w * (a[0] * x + a[1] * y + a[2] * z + a[3])
					+ w2 * (b[0] * x + b[1] * y + b[2] * z + b[3]));
			points[i * 3 + 1] = (float) (w * (a[4] * x + a[5] * y + a[6] * z + a[7])
					+ w2 * (b[4] * x + b[5] * y + b[6] * z + b[7]));
			points[i * 3 + 2] = (float) (w * (a[8] * x + a[9] * y + a[10] * z + a[11])
					+ w2 * (b[8] * x + b[9] * y + b[10] * z + b[11]));
		}
		// Se copian todos los puntos de golpe: mucho más rápido que uno a uno,
		// porque JavaFX solo tiene que avisar del cambio una vez.
		mesh.getPoints().set(0, points, 0, points.length);
	}

	public TriangleMesh getMesh() {
		return mesh;
	}

	public Segment getSegment() {
		return segment;
	}

	public int getVertexCount() {
		return vertexCount;
	}

	/** Interpolación lineal: k = 0 da a, k = 1 da b, valores intermedios en línea recta. */
	private static double lerp(double a, double b, double k) {
		return a + (b - a) * k;
	}

	/** Recorta x al intervalo [0, 1]. */
	private static double clamp(double x) {
		return Math.max(0, Math.min(1, x));
	}
}
