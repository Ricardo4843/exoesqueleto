package exoesqueleto.kinematics;

// "import static" permite escribir TUBE en vez de Segment.Shape.TUBE
import static exoesqueleto.kinematics.Segment.Shape.ELLIPSOID;
import static exoesqueleto.kinematics.Segment.Shape.TUBE;

/**
 * Construye el esqueleto humano de cuerpo completo (~1,75 m, 25 segmentos)
 * con límites articulares aproximados. Sustituye al fichero de texto que se
 * leía en el lab2.
 *
 * <h2>Ejes del mundo</h2>
 * X = lateral (hacia la derecha del sujeto), Y = hacia delante, Z = arriba.
 * El suelo es z = 0 y la raíz (pelvis) está a 95 cm de altura.
 *
 * <h2>Árbol</h2>
 *
 * <pre>
 * Pelvis (raíz, longitud 0)
 * ├── Lumbar ── Tórax ──┬── Cuello ── Cabeza
 * │                     ├── Clavícula D ── Brazo D ── Antebrazo D ── Mano D
 * │                     └── Clavícula I ── Brazo I ── Antebrazo I ── Mano I
 * ├── Cadera D ── Muslo D ── Tibia D ── Pie D
 * └── Cadera I ── Muslo I ── Tibia I ── Pie I
 * </pre>
 *
 * Es un árbol (y no una simple cadena como un brazo robótico) porque el
 * cuerpo se ramifica: de la pelvis salen el tronco y las dos piernas, y del
 * tórax salen el cuello y los dos brazos.
 *
 * <h2>Truco para que los dos lados sean simétricos</h2>
 * Todos los segmentos de piernas y brazos acaban, en reposo, con la misma
 * orientación: rotX(180º), es decir, Z local hacia abajo y X local hacia la
 * derecha. Así, en los dos lados, el eje X es siempre flexión (positivo =
 * hacia delante) y el Y es la abducción (separar del cuerpo).
 *
 * Para conseguirlo, la cadera y la clavícula giran rotY(±90º) para apuntar
 * hacia el lado, y el muslo y el brazo deshacen ese giro con rotY(∓90º) antes
 * de girar 180º hacia abajo. Como cadera * muslo = rotY(90) * rotY(-90) *
 * rotX(180) = rotX(180), el resultado es igual en los dos lados.
 */
public class HumanSkeleton {

	/** Altura de la pelvis sobre el suelo (cm). Es el origen de la cinemática. */
	public static final double PELVIS_HEIGHT = 95;

	// Constantes de 90º y 180º en radianes, para no escribir Math.PI / 2 cada vez
	private static final double D90 = Math.PI / 2, D180 = Math.PI;

	/**
	 * Crea el esqueleto y devuelve su raíz (la pelvis). Todos los ángulos de
	 * .joint(...) van en grados: (eje, nombre, mínimo, máximo).
	 */
	public static Segment build() {
		// ---------------- Tronco ----------------
		// Raíz: longitud 0, sirve de punto de unión del tronco y las piernas. Su
		// eje Z (Giro) permite rotar todo el cuerpo de -180º a 180º.
		Segment pelvis = new Segment("Pelvis", 0)
				.joint(0, "Inclinación", -30, 30)
				.joint(1, "Lateral", -30, 30)
				.joint(2, "Giro", -180, 180);

		// Sin rotación base: sigue hacia arriba (+Z), como la pelvis.
		// armorStart(-12): la armadura empieza 12 cm por debajo, para tapar la
		// pelvis (que no tiene pieza propia).
		Segment lumbar = new Segment("Lumbar", 20)
				.joint(0, "Flexión", -20, 45)
				.joint(1, "Lateral", -25, 25)
				.joint(2, "Giro", -30, 30)
				.armor(TUBE, 15, 10, 14, 9.5).armorStart(-12);
		// Más ancho arriba (17 cm) que abajo (14,5 cm): forma de tórax
		Segment chest = new Segment("Tórax", 28)
				.joint(0, "Flexión", -15, 30)
				.joint(1, "Lateral", -15, 15)
				.joint(2, "Giro", -30, 30)
				.armor(TUBE, 14.5, 10, 17, 11);
		Segment neck = new Segment("Cuello", 10)
				.joint(0, "Flexión", -40, 50)
				.joint(1, "Lateral", -35, 35)
				.joint(2, "Giro", -70, 70)
				.armor(TUBE, 5.5, 5.5, 5, 5);
		// Sin .joint(1, ...): la cabeza no tiene inclinación lateral propia (eje Y
		// bloqueado; esa inclinación la hace el cuello)
		Segment head = new Segment("Cabeza", 23)
				.joint(0, "Flexión", -20, 20)
				.joint(2, "Giro", -20, 20)
				.armor(ELLIPSOID, 9, 10.5, 9, 10.5);
		pelvis.addChild(lumbar);
		lumbar.addChild(chest);
		chest.addChild(neck);
		neck.addChild(head);

		// ---------------- Extremidades ----------------
		// Se construyen en un bucle para los dos lados: side = 1 (derecho) y
		// side = -1 (izquierdo). Multiplicar los ángulos por "side" refleja el
		// lado izquierdo como en un espejo, sin duplicar código.
		for (int side : new int[] { 1, -1 }) {
			String s = side == 1 ? " D" : " I"; // sufijo del nombre: Derecho / Izquierdo

			// --- Pierna ---
			// Cadera: segmento "de unión" que va de la pelvis hacia el lado. Sin
			// armadura ni ejes libres (los movimientos de cadera están en el muslo).
			Segment hip = new Segment("Cadera" + s, 9, Matrix4.rotY(side * D90));
			// Muslo: deshace el giro lateral y apunta hacia abajo (ver javadoc).
			// La abducción tiene límites asimétricos: separar la pierna hacia
			// fuera llega a 45º, pero cruzarla hacia dentro solo a 20º. Como en
			// los dos lados positivo = hacia la derecha, los límites se invierten.
			Segment thigh = new Segment("Muslo" + s, 45,
					Matrix4.rotY(-side * D90).multiply(Matrix4.rotX(D180)))
					.joint(0, "Flexión", -30, 120)
					.joint(1, "Abducción", side == 1 ? -20 : -45, side == 1 ? 45 : 20)
					.joint(2, "Rotación", -40, 40)
					.armor(TUBE, 9, 9.5, 6.5, 6.5);
			// Tibia: continúa al muslo (sin base). La rodilla es una bisagra: solo
			// tiene libre el eje X, y solo hacia valores negativos (hacia atrás).
			Segment shin = new Segment("Tibia" + s, 43)
					.joint(0, "Flexión", -140, 0)
					.armor(TUBE, 6.5, 6.5, 4.5, 4.5);
			// Pie: rotX(90º) lo gira de "hacia abajo" a "hacia delante".
			Segment foot = new Segment("Pie" + s, 20, Matrix4.rotX(D90))
					.joint(0, "Flexión", -30, 45)
					.joint(1, "Inversión", -20, 20)
					.armor(ELLIPSOID, 5, 4, 5, 4);
			pelvis.addChild(hip);
			hip.addChild(thigh);
			thigh.addChild(shin);
			shin.addChild(foot);

			// --- Brazo (misma lógica que la pierna) ---
			Segment clavicle = new Segment("Clavícula" + s, 21, Matrix4.rotY(side * D90))
					.joint(0, "Elevación", -10, 30);
			// El hombro es la articulación con más rango del cuerpo: los 3 ejes
			// libres y flexión hasta 180º (brazo apuntando al techo).
			Segment arm = new Segment("Brazo" + s, 30,
					Matrix4.rotY(-side * D90).multiply(Matrix4.rotX(D180)))
					.joint(0, "Flexión", -60, 180)
					.joint(1, "Abducción", side == 1 ? -20 : -170, side == 1 ? 170 : 20)
					.joint(2, "Rotación", -90, 90)
					.armor(TUBE, 6.5, 6.5, 5, 5);
			// Codo: flexión (X) y pronación/supinación del antebrazo (Z, girar
			// la muñeca alrededor del propio antebrazo).
			Segment forearm = new Segment("Antebrazo" + s, 27)
					.joint(0, "Flexión", 0, 145)
					.joint(2, "Pronación", -80, 80)
					.armor(TUBE, 5, 5, 3.8, 3.5);
			// Mano: elipsoide plano (2,2 cm de grosor x 4,5 cm de ancho)
			Segment hand = new Segment("Mano" + s, 18)
					.joint(0, "Flexión", -70, 80)
					.joint(1, "Desviación", -20, 30)
					.armor(ELLIPSOID, 2.2, 4.5, 2.2, 4.5);
			chest.addChild(clavicle);
			clavicle.addChild(arm);
			arm.addChild(forearm);
			forearm.addChild(hand);
		}
		return pelvis;
	}
}
