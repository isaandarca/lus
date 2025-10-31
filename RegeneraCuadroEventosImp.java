//regenerarcuadroeventosimp.java


/**
 * Esta clase es la encaragada de volver a calcular los eventos de una operación
 * que deben reflejar cambios introducidosen ella, alta de un cobro, evento...
 *
 * El proceso comun es la eliminacion de saldos y eventos de una operación a
 * partir de una fecha y ejecución de los planes para volver a generar los
 * eventos reflejando los cambios en sus calculos.
 *
 * Los eventos que se mantendrán o volverán a generan estan definidos por cada
 * uno de los métodos de llamada al cuadro.
 *
 * @author Oesia
 *
 */
@Stateless(name = "RegeneraCuadroEventos", mappedName = "RegeneraCuadroEventos")
@Local(RegeneraCuadroEventos.class)
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class RegeneraCuadroEventosImp implements RegeneraCuadroEventos {

	/**
	 * Service de RegenerarCuadroEventoshelper.
	 */
	@EJB
	private RegeneraCuadroEventosHelper helper;

	/**
	 * Service del Mensajero.
	 */
	@EJB
	private MensajeroMediadorService mensajeroMediadorService;

	/**
	 * Service de OperacionJDBC.
	 */
	@EJB
	private OperacionJDBC operacionJDBC;

	/**
	 * Service OperacionActivoDAO.
	 */
	@EJB
	private OperacionActivoDAO operacionDAO;

	/**
	 * Service de Aplicación días hábiles.
	 */
	@EJB
	private AplicacionDiasHabilesServiceLocal aplicacionDiasHabiles;

	/**
	 * Interfaz para generar los movimientos de NB.
	 */
	@EJB
	private InterfazNucleoDAOLocal interfazNucleoDAO;

	/**
	 * Service de disposición.
	 */
	@EJB
	private DisposicionService disposicionService;
	
	/**
	 * Service de saldos.
	 */
	@EJB
	SaldosServiceLocal saldosService;

	/**
	 * DataSource.
	 */
	@Resource(name = JndiUtils.JNDI, mappedName = JndiUtils.JNDI)
	private DataSource dataSource;
	
	/**
Service de eventosDAO
*/
	@EJB
	public EventosDAO eventosDAO;

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación desde su formalización.
	 * @param operacion
	 *                operación a regenerar
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regenera(Operacion operacion) throws CuadroEventoException {
		regenera(operacion, operacion.getFechaFormalizacion(), false);
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación desde una fecha dada.
	 * @param operacion
	 *                operación a regenerar
	 * @param fecha
	 *                fecha de regeneración
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regenera(Operacion operacion, Boolean isRecalculoAmortizaciones, Date fecha)
			throws CuadroEventoException {
		if (fecha.after(operacion.getFechaFormalizacion())) {
			regeneraCuadroEventos(operacion, null, fecha, false, isRecalculoAmortizaciones);
		} else {
			regeneraCuadroEventos(operacion,
					operacion.getFormalizacionOperacion(),
					fecha, false, isRecalculoAmortizaciones);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación desde una fecha dada indicando si deben mantenerse los
	 * eventos que afecan al capital.
	 * @param operacion
	 *                operación a regenerar
	 * @param fecha
	 *                fecha de regeneración
	 * @param sinCambioCapital
	 *                indica si deben mantenerse los eventos que afecan al
	 *                capital
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regenera(Operacion operacion, Date fecha,
			Boolean sinCambioCapital) throws CuadroEventoException {
		if (fecha.after(operacion.getFechaFormalizacion())) {
			regeneraCuadroEventos(operacion, null, fecha,
					sinCambioCapital, false);
		} else {
			regeneraCuadroEventos(operacion,
					operacion.getFormalizacionOperacion(),
					fecha, sinCambioCapital, false);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de la
	 * operación a la que pertenece un evento desde una fecha dada.
	 * @param evento
	 *                evento cuya operación se quiere regenerar
	 * @param fecha
	 *                fecha de regeneración
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regenera(Evento evento, Date fecha)
			throws CuadroEventoException {
		regeneraCuadroEventos(evento.getOperacion(), evento, fecha,
				false, false);
	}

	/**
	 * Punto de entrada para la regeneración del cuadro con amortizaciones.
	 * @param evento Evento
	 * @param fecha Date
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraAmortizacion(Evento evento, Date fecha)
			throws CuadroEventoException {
		regeneraCuadroEventosAmortizacion(evento.getOperacion(), evento,
				fecha, false);
	}

	/**
	 * Método encargado de regenerar el cuadro de eventos
	 * cuando se vienen de una baja de una liquidación de interés.
	 * @param liqInteres LiquidacionInterses
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraCuadroEventosBajaLiquidacionInteres(
			LiquidacionIntereses liqInteres)
			throws CuadroEventoException {
		EventosOperacion evOp = new EventosOperacion();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();
		boolean hayQueRegenerarSubsidiosDiaMas = false;
		HashMap<Date, List<Cobro>> cobros = null;
		List<CobroEvento> cobrosEventos = null;
		Operacion o = helper.loadOperacion(
				liqInteres.getOperacion().getId());
		Long idOperacion = o.getId();

		try {
			fIniPrimerEv = inicializaCuadroEventos(o, liqInteres,
					liqInteres.getFechaEvento(), evOp,
					idsDisposicion, planes);
			Date fEjec = helper.getFechaInicioLiqManualIntermedia(
					idOperacion,
					liqInteres.getFechaEvento());
			List<Evento> eventos = new ArrayList<Evento>();
			if (helper.hayDisposicionesCapitalizacionPosteriores(o,
					fEjec)) {
				helper.getEventosAfectanCPDia(o, eventos, fEjec,
						evOp, false);
			} else {
				helper.eliminaCobrosEventos(o, fEjec);
				helper.getEventosMantenerFijacionTipos(o,
						eventos, fEjec, evOp, false,false);
				borrarPlanAmortizacion(planes);
			}
			helper.getAmortAnterioresAltaCobro(o, fEjec, evOp);
			eliminarPlanesComisionAmoAnticipadaFijo(eventos, planes);
			if (o.getFormalizacionOperacion() != null) {
				eventos.add(o.getFormalizacionOperacion());
			}
			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(o, fEjec,
							evOp);
			if (eventosAnteriores != null
					&& eventosAnteriores.size() > 0) {
				for (int i = 0; i < eventosAnteriores
						.size(); i++) {
					evOp.addEventosNoCobrados(
							eventosAnteriores.get(
									i));
				}
			}
			addEventosAutomaticos(eventos, evOp);
			List<Long> idEventos = new ArrayList<Long>();
			cobros = new HashMap<Date, List<Cobro>>();
			List<Long> idsEventoMantener = getIdsEventos(eventos);
			helper.setFechaInterfazContable(idOperacion, fEjec);
			if (o.getPlanesSubsidio() != null
					&& o.getPlanesSubsidio().size() > 0) {
				helper.eliminaCobrosSubsidios(idOperacion,
						fEjec);
			}
			helper.eliminaSaldosEventos(idOperacion, fEjec,
					idsEventoMantener,
					mensajeroMediadorService, idEventos,
					false, o.getTipoOperacionActivo().getCodigo());//ICO-65728
			interfazNucleoDAO.generaMovimientosNB(idEventos);
			Date fechaDesdeCobroSubsidio = fEjec;
			cobrosEventos = new ArrayList<CobroEvento>();
			Date fecGenAmort = fEjec;
//			fecGenAmort = 
			inicializarParametros(eventos, idsDisposicion, fecGenAmort, fEjec);
			SaldosTotalesOp saldos = helper.consultaSaldos(o,
					idsDisposicion, fIniPrimerEv, fEjec,
					false);
			try {
				List<SaldosOp> saldosExceso = helper.undoCobros(
						fEjec, o, cobros,
						idsEventoMantener, false,
						false);
				saldos.mezclarSaldosExceso(saldosExceso);
			} catch (Exception e) {
				throw new CuadroEventoException(e);
			}
			PlanAjustableDias planAjuste = o.getPlanAjustableDias();
			String calRen = o.getCalendarioRenovacion();
			List<Date> festivos = aplicacionDiasHabiles
					.getDiasFesivos(planAjuste, calRen);
			if (eventos != null && eventos.size() != 0) {
//				fecGenAmort = FechaUtils.sumaUnDiaCalendario(
//						fecGenAmort);
				addNoCobrados(eventos, evOp);
			}
			ejecutarEventos(eventos, fEjec, idOperacion,
					idsDisposicion, evOp, saldos, cobros,
					cobrosEventos, planes);
			evOp.setDemorasAnteriores(
					helper.demorasAnteriores(o, fEjec));
			if (!existeAnticipada(eventos)) {
				borrarPlanesInnecesarios(planes);
			}
			evOp.setCodigoProducto(
					helper.buscarProducto(idOperacion));
			ejecutarPlanesBajaLiquidacionInteres(planes, evOp,
					idOperacion, idsDisposicion, saldos,
					cobros, cobrosEventos,
					hayQueRegenerarSubsidiosDiaMas, fEjec,
					fechaDesdeCobroSubsidio, fecGenAmort,
					festivos);
			if (evOp.getEventosConFechaCambiada() != null
					&& !evOp.getEventosConFechaCambiada()
							.isEmpty()) {
				Iterator<Evento> it = evOp
						.getEventosConFechaCambiada()
						.iterator();
				while (it.hasNext()) {
					Evento ev = it.next();
					List<Evento> evs = null;
					evs = new ArrayList<Evento>();
					evs.add(ev);
					ejecutarEventos(evs,
							ev.getFechaEvento(),
							idOperacion,
							idsDisposicion, evOp,
							saldos, cobros,
							cobrosEventos, planes);
					it.remove();
				}
				evOp.removeEventosConFechaCambiada();
			}

			evOp.doSaldos(saldos, cobros, idOperacion,
					idsDisposicion, fEjec,
					fechaDesdeCobroSubsidio, cobrosEventos, festivos, o);
			helper.insertaSaldosEventos(o, saldos,
					evOp.getEventosGenerados(), cobros,
					fechaDesdeCobroSubsidio, eventos,
					cobrosEventos);
			operacionJDBC.actualizarCheckCuadroActualizado(
					idOperacion, true);
			eliminarActualizarEventos(o.getId());

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Ejecuta los planes de evento cuando se viene de una baja de
	 * liquidación de interés.
	 * @param planes List<PlanEvento>
	 * @param evOp EventosOperacion
	 * @param idOperacion Long
	 * @param idsDisposicion List<Long
	 * @param saldos SaldosTotalesOp
	 * @param cobros HashMap<Date, List<Cobro>>
	 * @param cobrosEventos List<CobroEvento>
	 * @param hayQueRegenerarSubsidiosDiaMas boolean
	 * @param fEjec Date
	 * @param fechaDesdeCobroSubsidio Date
	 * @param fecGenAmort Date
	 * @param festivos List<Date>
	 * @throws POJOValidationException POJOValidationException
	 */
	private void ejecutarPlanesBajaLiquidacionInteres(
			List<PlanEvento> planes, EventosOperacion evOp,
			Long idOperacion, List<Long> idsDisposicion,
			SaldosTotalesOp saldos,
			HashMap<Date, List<Cobro>> cobros,
			List<CobroEvento> cobrosEventos,
			boolean hayQueRegenerarSubsidiosDiaMas, Date fEjec,
			Date fechaDesdeCobroSubsidio, Date fecGenAmort,
			List<Date> festivos) throws POJOValidationException {
		for (PlanEvento plan : planes) {
			TipoPlanEventoEnum t = plan.getTipoPlanEvento();
			plan.setFechaFinalSimulacion(null);

			if (plan.necesitaSaldosActualizados()) {

				necesitaSaldosActualizados(evOp, idOperacion,
						idsDisposicion, saldos, cobros,
						cobrosEventos, fEjec,
						fechaDesdeCobroSubsidio, planes);
			}

			List<EventoAutomatico> generados = null;
			generados = new ArrayList<EventoAutomatico>();

			if ((isPlanAmort(t)) || (hayQueRegenerarSubsidiosDiaMas
					&& isPlanSubsidio(t))) {
				generados = plan.doEventos(fecGenAmort, saldos,
						evOp, festivos, null);
			} else {
				if (isPlanDemora(t)) {

					setFechaPrimerVencDem(
							(PlanDemora) plan);
					generados = plan.doEventos(fEjec,
							saldos, evOp, festivos,
							cobros);
				} else {
					Date f = fEjec;
					if (isPlanInteres(t)) {
						f = FechaUtils.sumarDias(
								fEjec, 1);
					}
					generados = plan.doEventos(
							f, saldos,
							evOp, festivos,
							null);
				}
			}

			evOp.addEventosPlan(plan, generados);

			for (Evento eventoGenerado : generados) {
				evOp.addEventosNoCobrados(eventoGenerado);
			}
		}
	}

	/**
	 * Actualiza los saldos necesarios.
	 * @param evOp EventosOperacion
	 * @param idOperacion Long
	 * @param idsDisposicion List<Long>
	 * @param saldos SaldosTotalesOp
	 * @param cobros HashMap<Date, List<Cobro>>
	 * @param cobrosEventos List<CobroEvento>
	 * @param fEjec Date
	 * @param fechaDesdeCobroSubsidio Date
	 * @throws POJOValidationException POJOValidationException
	 */
	private void necesitaSaldosActualizados(EventosOperacion evOp,
			Long idOperacion, List<Long> idsDisposicion,
			SaldosTotalesOp saldos,
			HashMap<Date, List<Cobro>> cobros,
			List<CobroEvento> cobrosEventos, Date fEjec,
			Date fechaDesdeCobroSubsidio, List<PlanEvento> planes)
			throws POJOValidationException {
		if (evOp.getEventosConFechaCambiada() != null && !evOp
				.getEventosConFechaCambiada().isEmpty()) {
			Iterator<Evento> it = evOp.getEventosConFechaCambiada()
					.iterator();

			while (it.hasNext()) {
				Evento ev = it.next();
				List<Evento> evs = new ArrayList<Evento>();
				evs.add(ev);

				ejecutarEventos(evs, ev.getFechaEvento(),
						idOperacion, idsDisposicion,
						evOp, saldos, cobros,
						cobrosEventos, planes);
				it.remove();
			}
			evOp.removeEventosConFechaCambiada();
		}

		evOp.doSaldos(saldos, cobros, idOperacion, idsDisposicion,
				fEjec, fechaDesdeCobroSubsidio, cobrosEventos, null, null);
	}

	/**
	 * Método encargado de regenerar el cuadro de eventos
	 * cuando se viene del mantenimiento de disposiciones.
	 * @param disposicion DisposicionOperacion
	 * @param fEjec Date
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regenera(DisposicionOperacion disposicion, Date fEjec)
			throws CuadroEventoException {

		// deja la fecha de ejecucion sin hora
		fEjec = FechaUtils.truncateDate(fEjec);

		// Contiene los eventos que se están regenerando y sobre los que
		// se
		// recalcularán los saldos
		EventosOperacion evOp = new EventosOperacion();

		Operacion o = disposicion.getOperacion();
		Operacion opEventoManualEELL = disposicion.getOperacion();

		// carga datos necesarios de la operacion
		o = helper.loadOperacion(o.getId());
		if (disposicion != null && disposicion.getEsEstadoActivo()) {
			// carga datos necesarios del evento
			disposicion = (DisposicionOperacion) helper.loadEvento(
					disposicion.getId(), evOp, o, fEjec);
		}

		Long idOperacion = o.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();
		boolean hayQueRegenerarSubsidiosDiaMas = false;
		boolean fijacionManualDemoras = false;
		
		helper.obtenerFechaFinAmortizacionCaratula(idOperacion, evOp);

		try {
			inicializaCuadroEventos(o, disposicion, fEjec, evOp,
					idsDisposicion, planes);
			
			// INI - ICO-60274 - 15/06/2020 - Si operacion es FD, con tipo de demora fijo y fijacion manual, no se ejecuta el plan de demoras
			if(o.getPlanDemora() != null && o.getPlanDemora().getPlanInteresPorDefectoVigente() != null && o.getPlanDemora().getPlanInteresPorDefectoVigente().getFijacionManualDemoras() != null) {
				fijacionManualDemoras = o.getPlanDemora().getPlanInteresPorDefectoVigente().getFijacionManualDemoras();
			}
			
			if((TipoOperacionActivoEnum.FD.getCodigo().equals(o.getTipoOperacionActivo().getCodigo()) 
					|| TipoOperacionActivoEnum.VPO.getCodigo().equals(o.getTipoOperacionActivo().getCodigo())) && fijacionManualDemoras) {
				borrarPlanDemora(planes);
			}
			// FIN - ICO-60274 - 15/06/2020

			fIniPrimEv = o.getPrimeraDisposicion().getFechaEvento();

			// ICO-47779
			if (fIniPrimEv != null) {
				fIniPrimEv = FechaUtils.restaUnDiaCalendario(o
						.getPrimeraDisposicion()
						.getFechaEvento());
			}

			// comprueba si estamos regenerando dentro del periodo
			// de unja
			// liquidacion manual
			// para en ese caso regenerar desde la fecha de inicio
			// de esta
			// liquidacion.
			// como se va a eliminar debe volver liquidarse todo el
			// periodo
			// comprendido entre sus fechas
			fEjec = helper.getFechaInicioLiqManualIntermedia(
					idOperacion, fEjec);

			helper.eliminaCobrosEventos(o, fEjec);

			List<Evento> eventos = helper
					.getEventosRegeneracionDisposicion(o,
							fEjec, evOp, contieneEventoManualEELL(opEventoManualEELL)); //ICO-77191 Añadir isDevolucionFactura

			if (o.getFormalizacionOperacion() != null) {
				eventos.add(o.getFormalizacionOperacion());
			}
			if (disposicion != null) {
				eventos.add(disposicion);
			}
			//INI ICO-68057
			helper.getEventosDevolucionFactura(o, fEjec, evOp, disposicion);
			
			//FIN ICO-68057

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(o, fEjec,
							evOp);

			if (eventosAnteriores != null
					&& !eventosAnteriores.isEmpty()) {
				for (int i = 0; i < eventosAnteriores
						.size(); i++) {

					evOp.addEventosNoCobrados(
							eventosAnteriores.get(
									i));
				}

			}

			if (eventos != null && !eventos.isEmpty()) {
				for (Evento ev : eventos) {
					if (ev instanceof EventoAutomatico) {
						evOp.addEventosNoCobrados(ev);
					}
				}
			}

			if (disposicion.getNumEvento() > 1 || disposicion
					.getDisposicionCapitalizacion()) {
				List<Evento> a = helper
						.getAmortCalendarioAFecha(o,
								fEjec, evOp);

				for (Evento ev : a) {
					evOp.addEventosNoCobrados(ev);
					if (!eventos.contains(ev)) {
						eventos.add(ev);
					}

				}
			}

			regeneraCuadroEventos(o, idsDisposicion, planes, evOp,
					eventos, fIniPrimEv, fEjec, false,
					false, hayQueRegenerarSubsidiosDiaMas,
					false, false, false, false);

			eliminarActualizarEventos(o.getId());

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método encargado de la regeneración del cuadro de eventos
	 * cuando se viene del mantenimiento de comisiones.
	 * @param o Operacion
	 * @param fEjec Date
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraComisiones(Operacion o, Date fEjec)
			throws CuadroEventoException {
		EventosOperacion evOp = new EventosOperacion();
		o = helper.loadOperacion(o.getId());
		Long idOperacion = o.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();

		try {
			fIniPrimerEv = inicializaCuadroEventos(o,
					o.getFormalizacionOperacion(), fEjec,
					evOp, idsDisposicion, planes);

			helper.eliminaCobrosEventosFromEvento(o, fEjec);

			List<Evento> eventos = helper
					.getEventosMantenerAltaComisiones(o,
							fEjec, evOp);

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(o, fEjec,
							evOp);

			addAllEventosNoCobrados(eventosAnteriores, evOp);

			for (Evento ev : eventos) {
				if (ev.isManual()) {
					o.addEventoManualToSet(
							(EventoManual) ev);
				}
				evOp.addEventosNoCobrados(ev);
			}

			Iterator<PlanEvento> it = planes.iterator();
			while (it.hasNext()) {
				PlanEvento plan = it.next();
				TipoPlanEventoEnum t = plan.getTipoPlanEvento();
				if (!(isPlanDemora(t) || isPlanSubsidio(t))) {
					it.remove();
				}
			}

			evOp.setBajaCobro(true);
			regeneraCuadroEventos(o, idsDisposicion, planes, evOp,
					eventos, fIniPrimerEv, fEjec, false,
					false, false, false, false, false,
					true);

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método para regenerar los saldos cuando se viene de un
	 * recálculo de intereses manuales.
	 * @param o Operacion
	 * @param fEjec Date
	 * @param subsidios boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraSaldosIntereses(Operacion o, Date fEjec,
			boolean subsidios) throws CuadroEventoException {
		EventosOperacion evOp = new EventosOperacion();
		o = helper.loadOperacion(o.getId());
		Long idOperacion = o.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();
		HashMap<Date, List<Cobro>> cobros = null;
		List<CobroEvento> cobrosEventos = null;

		try {
			fIniPrimerEv = inicializaCuadroEventosSaldos(o,
					o.getFormalizacionOperacion(), fEjec,
					evOp, idsDisposicion, planes);

			if (!subsidios) {
				helper.eliminaCobrosEventosFromEvento(o, fEjec);
			} else {
				helper.eliminaCobrosEventosFromEventoSubsidios(
						o, fEjec);
			}

			List<Evento> eventos = helper.getSiguientesEventos(o,
					fEjec, evOp);

			for (Evento ev : eventos) {
				if (ev.isManual()) {
					o.addEventoManualToSet(
							(EventoManual) ev);
				}
			}

			List<Long> idsEventoMantener = getIdsEventos(eventos);
			helper.eliminaSaldosEventos(idOperacion, fEjec,
					idsEventoMantener,
					mensajeroMediadorService,
					new ArrayList<Long>(), false, o.getTipoOperacionActivo().getCodigo());

			Iterator<PlanEvento> it = planes.iterator();
			while (it.hasNext()) {
				TipoPlanEventoEnum t = it.next()
						.getTipoPlanEvento();
				if (!(isPlanDemora(t) || isPlanSubsidio(t))) {
					it.remove();
				}
			}

			cobros = new HashMap<Date, List<Cobro>>();
			cobrosEventos = new ArrayList<CobroEvento>();

			helper.setFechaInterfazContable(idOperacion, fEjec);

			SaldosTotalesOp saldos = generaCuadroEventos(o,
					idsDisposicion, planes, evOp, eventos,
					idsEventoMantener, fIniPrimerEv, fEjec,
					cobros, false, false, false, false,
					false, fEjec, cobrosEventos);

			helper.insertaSaldosEventos(o, saldos,
					evOp.getEventosGenerados(), cobros,
					null, eventos, cobrosEventos);

			operacionJDBC.actualizarCheckCuadroActualizado(
					idOperacion, true);

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método para regenerar el cuadro desde un recálculo de saldos.
	 * @param o Operacion
	 * @param fechaEjecucion Date
	 * @param subsidios boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraSaldos(Operacion o, Date fechaEjecucion,
			boolean subsidios) throws CuadroEventoException {
		EventosOperacion evOp = new EventosOperacion();
		o = helper.loadOperacion(o.getId());
		Long idOperacion = o.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();

		try {
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Inicia regenera saldos");
			}
			
			fIniPrimerEv = inicializaCuadroEventosSaldos(o,
					o.getFormalizacionOperacion(),
					fechaEjecucion, evOp, idsDisposicion,
					planes);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Ha obtenido de BD las disposiciones, liquidaciones anteriores, planes de ejecución y la fecha inicio primer evento.");
			}

			if (!subsidios) {
				helper.eliminaCobrosEventosFromCobro(o,
						fechaEjecucion);
			} else {
				helper.eliminaCobrosEventosFromEventoSubsidios(
						o, fechaEjecucion);
			}
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Ha eliminado de pa_cobroevento todas las filas relacionadas con esta operación cuya fecha sea > a la de ejecución.");
			}

			List<Evento> eventos = helper.getRecuperarSiguientes(o,
					fechaEjecucion, evOp);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Ha recuperado los eventos con fecha > a la fecha ejecución (fecha evento de la amortización modificada)");
			}

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(o,
							fechaEjecucion, evOp);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Ha recuperado los eventos con fecha < a la fecha ejecución (fecha evento de la amortización modificada)");
			}

			helper.getEventosMantenerComisionesManuales(o, eventos,
					fechaEjecucion, evOp);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Ha recuperado las comisiones manuales");
			}

			addAllEventosNoCobrados(eventosAnteriores, evOp);

			addEventosRecalculoSaldos(eventos, evOp, o);

			addEventosManuales(eventos, o);

			planes.clear();

			evOp.setBajaCobro(true);
			
			regeneraCuadroEventos(o, idsDisposicion, planes, evOp,
					eventos, fIniPrimerEv, fechaEjecucion,
					false, false, false, false, false,
					false, true);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					o.getId().toString() )) {
				LOG_ICO_62556.info("Ha terminado regenera cuadro eventos");
			}

			helper.setFechaInterfazContable(idOperacion,
					fechaEjecucion);

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Añade los eventos al pull cuando se viene un recálculo
	 * de saldos.
	 * @param eventos List<Evento>
	 * @param evOp EventosOperacion
	 * @param o Operacion
	 */
	private void addEventosRecalculoSaldos(List<Evento> eventos,
			EventosOperacion evOp, Operacion o) {
		for (Evento ev : eventos) {
			if (ev.isManual()) {
				o.addEventoManualToSet((EventoManual) ev);
			}
			if (ev instanceof LiquidacionInteresesAutomatica) {
				if (((EventoAutomatico) ev)
						.getEventoTotal() == null) {
					evOp.addEventosNoCobrados(ev);
				} else {
					continue;
				}
			} else {
				if (isAutomaticoYTotal(ev)) {
					evOp.addEventosNoCobrados(ev);
				}
			}

		}
	}

	/**
	 * Comprueba si el evento es automático y total.
	 * @param e Evento
	 * @return boolean
	 */
	private boolean isAutomaticoYTotal(Evento e) {
		return e instanceof EventoAutomatico && ((EventoAutomatico) e)
				.getEventoTotal() == null;
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de la
	 * operación a la que pertenece un evento desde una fecha dada,
	 * indicando si deben mantenerse los eventos que afecan al capital.
	 * @param evento
	 *                evento cuya operación se quiere regenerar
	 * @param fecha
	 *                fecha de regeneración
	 * @param sinCambioCapital
	 *                indica si deben mantenerse los eventos que afecan al
	 *                capital
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regenera(Evento evento, Date fecha,
			Boolean sinCambioCapital) throws CuadroEventoException {
		regeneraCuadroEventos(evento.getOperacion(), evento, fecha,
				sinCambioCapital, false);
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de la
	 * operación a la que pertenece un evento desde una fecha dada,
	 * indicando si deben mantenerse los eventos que afecan al capital.
	 * @param evento
	 *                evento cuya operación se quiere regenerar
	 * @param fecha
	 *                fecha de regeneración
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraBajaAva(Evento evento, Date fecha)
			throws CuadroEventoException {
		regeneraCuadroEventosBajaAva(evento.getOperacion(), evento,
				fecha);
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación por una fijación de tipos.
	 * @param planEvento
	 *                plan evento de la operacion a regenerar
	 * @param evento
	 *                evento de la operacion a regenerar
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraFijacionTipos(PlanEvento planEvento, Evento evento)
			throws CuadroEventoException {
		
		//INIT ICO-57196
		try {
			helper.setFechaInterfazContable(planEvento.getOperacion().getId(), planEvento.getFechaEjecucion());
		} catch (Exception e) {e.fillInStackTrace();
			throw new RuntimeException(e.getMessage(), e);
		}
		//END ICO-57196
		
		Date fecha = FechaUtils.sumaUnDiaCalendario(
				planEvento.getFechaEjecucion());

		regeneraCuadroEventosFijacionTipos(planEvento.getOperacion(),
				evento, fecha, false,false);
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación por una fijación de tipos.
	 * @param operacion
	 *                operación a regenerar
	 * @param fecha
	 *                fecha de regeneración
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraFijacionTipos(Operacion operacion, Date fecha, boolean isRecalculoDemoras, boolean isRecalculoIntereses)
			throws CuadroEventoException {
		
		//INIT ICO-57196
		try {
			helper.setFechaInterfazContable(operacion.getId(), fecha);
		} catch (Exception e) {e.fillInStackTrace();
			throw new RuntimeException(e.getMessage(), e);
		}
		//END ICO-57196
		//if (!"VPO".equals(operacion.getTipoOperacionActivo().getNombre())) {
			fecha = FechaUtils.sumaUnDiaCalendario(fecha);
		//}
		if (operacion.getPrimeraDisposicion() != null && !FechaUtils
				.truncateDate(operacion.getPrimeraDisposicion()
						.getFechaEvento())
				.after(fecha)) {
			regeneraCuadroEventosFijacionTipos(operacion,
					operacion.getPrimeraDisposicion(),
					fecha, isRecalculoDemoras,isRecalculoIntereses);
		} else {
			regeneraCuadroEventosFijacionTipos(operacion, null,
					fecha, isRecalculoDemoras,isRecalculoIntereses);
		}
		
		//ICO-68064 y ICO-71545
		boolean isInteresFijo = operacion.getPlanInteres() != null && !operacion.getPlanInteres().isVariable();
		boolean isDemorasFijo = operacion.getPlanDemora() != null && !operacion.getPlanDemora().isVariable();
		
    	if(isDemorasFijo || isInteresFijo) {
    		try {
    			if (operacion instanceof OperacionEMImp) {
    				eventosDAO.executeProcedureSaldosEM(helper.getCodigoHostByOperacion(operacion.getId()));
    			} else if(operacion instanceof OperacionVPOImp) {
    				eventosDAO.executeProcedureSaldosVPO(helper.getCodigoHostByOperacion(operacion.getId()));
    			}else {
    				eventosDAO.executeProcedureSaldos(helper.getCodigoHostByOperacion(operacion.getId()));
    			}
			} catch (Exception e) {
				throw new CuadroEventoException(e.getMessage(), e);
			}
    	} // FIN ICO-68064 y ICO-71545
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación a partir de un cobro puntual.
	 * Este método realiza en primer lugar la regeneración de aval de la
	 * operación si tiene, seguido de la misma operación y de la operación
	 * avalada de esta en caso de ternerla.
	 * @param cobroPuntual
	 *                cobro de la operación a regenerar
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regenera(Cobro cobroPuntual, boolean isRecalculoComisiones) throws CuadroEventoException {
		try {
			EventosOperacion evOp = new EventosOperacion();

			regeneraCuadroEventosCobro(cobroPuntual, evOp, isRecalculoComisiones);

		} catch (Exception e) {
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera los eventos de
	 * una operación a partir de un cobro puntual.
	 * Este método realiza en primer lugar la regeneración de aval de la
	 * operación si tiene, seguido de la misma operación y de la operación
	 * avalada de esta en caso de ternerla.
	 * @param cobroPuntual
	 *                cobro de la operación a regenerar
	 * @param eliminarActualizarEventos boolean
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraMediacion(Cobro cobroPuntual,
			boolean eliminarActualizarEventos)
			throws CuadroEventoException {
		try {
			// Contiene los eventos que se están regenerando y sobre
			// los que se
			// recalcularán los saldos
			EventosOperacion evOp = new EventosOperacion();
			
			if(null != cobroPuntual && null != cobroPuntual.getOperacion() && cobroPuntual.getOperacion() instanceof OperacionEL) {
				evOp.setCobro(true);
			}
			
			// regenera el cuadro de la operacion
			regeneraCuadroEventosCobroMediacion(cobroPuntual,
					evOp,
					eliminarActualizarEventos);

		} catch (Exception e) {
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos para regenerar el
	 * préstamo cuando viene de un cobro de subsidio.
	 * @param cobroPuntual Cobro
	 * @param eliminarActualizarEventos boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraMediacionCobroSubsidio(Cobro cobroPuntual,
			boolean eliminarActualizarEventos)
			throws CuadroEventoException {
		try {
			EventosOperacion evOp = new EventosOperacion();

			regeneraCuadroEventosCobroSubsidio(cobroPuntual, evOp,
					eliminarActualizarEventos);
		} catch (Exception e) {
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera de una operación
	 * desde una fecha dada manteniendo los eventos manuales.
	 * Este método realiza en primer lugar la regeneración de aval de la
	 * operación si tiene, seguido de la misma operación y de la operación
	 * avalada de esta en caso de ternerla.
	 * @param operacion
	 *                operación a regenerar
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 * @param crearCobrosSubsidiosDiaDespues Boolean
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraCuadroEventosSinBorradoEM(Operacion operacion,
			Date fechaEjecucion,
			Boolean crearCobrosSubsidiosDiaDespues, //ICO-58380 Se añade parametro esBajaCobro para solucionar error al borrar, ICO-59300 se quita el parametro esBajaCobro por que interfiere en otras peticiones.
			Boolean esBajaCobro) //ICO-62994 Se vuelve incluir el parametro esBajaCobro para arreglar error al calcular demoras
			throws CuadroEventoException {
		try {
			EventosOperacion evOp = new EventosOperacion();

			evOp.updateBajaCobro(esBajaCobro);
			
			regeneraCuadroEventosSinBorradoEM(operacion,
					fechaEjecucion, evOp,
					crearCobrosSubsidiosDiaDespues);
		} catch (Exception e) {
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera la operación de
	 * una amortización por plazo desde una fecha dada.
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, preserva los eventos manuales de la operacion, no realiza
	 * el calculo de amortizaciones, estas las recibe ya ajustadas por
	 * parametro.
	 * @param amortizacion
	 *                amortizacion por plazo de la operación a regenerar
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 * @param amortizacionesAjustadas
	 *                lista de amortizaciones con el reajuste por plazo
	 *                realizado
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraCuadroEventosPlazo(Amortizacion amortizacion,
			Date fechaEjecucion,
			List<EventoAutomatico> amortizacionesAjustadas)
			throws CuadroEventoException {

		Long idOperacion = amortizacion.getOperacion().getId();
		EventosOperacion evOp = new EventosOperacion();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();

		Operacion o = helper.loadOperacion(idOperacion);

		if (amortizacion.getEsEstadoActivo()) {
			amortizacion = (Amortizacion) helper.loadEvento(
					amortizacion.getId(), evOp, o,
					fechaEjecucion);
		} else {
			amortizacion = null;
		}

		try {
			fIniPrimerEv = inicializaCuadroEventos(o, amortizacion,
					fechaEjecucion, evOp, idsDisposicion,
					planes);

			List<Evento> eventos = helper.getEventosManuales(o,
					fechaEjecucion, evOp);

			if (amortizacion != null) {
				eventos.add(amortizacion);
			}

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(o,
							fechaEjecucion, evOp);

			addAllEventosNoCobrados(eventosAnteriores, evOp);

			PlanEvento planAmortizacion = null;
			for (PlanEvento plan : planes) {
				if (isPlanAmort(plan.getTipoPlanEvento())) {
					planAmortizacion = plan;
				}
			}
			planes.remove(planAmortizacion);

			for (EventoAutomatico a : amortizacionesAjustadas) {
				a.setCobros(new HashSet<CobroEvento>());
				a.setId(null);
				a.setOperacion(o);
				a.setPlanEvento(planAmortizacion);
			}

			evOp.addEventosPlan(planAmortizacion,
					amortizacionesAjustadas);

			regeneraCuadroEventos(o, idsDisposicion, planes, evOp,
					eventos, fIniPrimerEv, fechaEjecucion,
					false, false, false, true, false,
					false, false);

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera la operación de
	 * una amortización por plazo desde una fecha dada.
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, preserva los eventos manuales de la operacion, no realiza
	 * el calculo de amortizaciones, estas las recibe ya ajustadas por
	 * parametro.
	 * @param amortizacion
	 *                amortizacion por plazo de la operación a regenerar
	 * @param fEjec
	 *                fecha de regeneración
	 * @param amortizacionesAjustadas
	 *                lista de amortizaciones con el reajuste por plazo
	 *                realizado
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraCuadroEventosCuotasSiguientes(
			Amortizacion amortizacion, Date fEjec,
			List<EventoAutomatico> amortizacionesAjustadas)
			throws CuadroEventoException {

		Long idOperacion = amortizacion.getOperacion().getId();
		EventosOperacion evOp = new EventosOperacion();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();

		Operacion o = helper.loadOperacion(idOperacion);

		Integer carencia = o.getPlanAmortizacion().getAnyosCarencia();

		Date fechaPrevista = o.getPlanAmortizacion()
				.getFechaAmortizacionPrevista();

		//ICO-56559 Se elimina la modificación de la fecha de ejecución
		
		if (amortizacion.getEsEstadoActivo()) {
			amortizacion = (Amortizacion) helper.loadEvento(
					amortizacion.getId(), evOp, o, fEjec);
		} else {
			amortizacion = null;
		}

		try {
			fIniPrimerEv = inicializaCuadroEventos(o, amortizacion,
					fEjec, evOp, idsDisposicion, planes);

			List<Evento> eventos = helper.getEventosManuales(o,
					fEjec, evOp);

			if (amortizacion != null) {
				eventos.add(amortizacion);
			}

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(o, fEjec,
							evOp);

			addAllEventosNoCobrados(eventosAnteriores, evOp);

			// elimina el plan de amortización del listado para que
			// no se
			// ejecute y recogemos la fecha del plan de amortización
			// Ésta fecha será la fecha de ejecucíon del recalculo
			// en caso de
			// que sea anterior a la fecha del Evento
			PlanEvento planAmortizacion = null;
			for (PlanEvento plan : planes) {
				if (isPlanAmort(plan.getTipoPlanEvento())) {
					planAmortizacion = plan;
				}
			}
			planes.remove(planAmortizacion);

			for (EventoAutomatico a : amortizacionesAjustadas) {
				a.setCobros(new HashSet<CobroEvento>());
				a.setId(null);
				a.setOperacion(o);
				a.setPlanEvento(planAmortizacion);
			}

			evOp.addEventosPlan(planAmortizacion,
					amortizacionesAjustadas);

			regeneraCuadroEventos(o, idsDisposicion, planes, evOp,
					eventos, fIniPrimerEv, fEjec, false,
					false, false, false, false, false,
					false);

			eliminarActualizarEventos(o.getId());

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que regenera la operación por
	 * una disposicion de capitalización
	 *
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, preserva los eventos manuales de la operacion, así como
	 * los que no se ven afectados por la capitalización.
	 *
	 * @param operacion
	 *                operación a regenerar
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 * @param eventosCapitalizados
	 *                colección de eventos capitalizados
	 * @param disposicionCapitalizacion
	 *                disposicion de capitalización
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	public void regeneraConCapitalizacion(Operacion operacion,
			Date fechaEjecucion, Set<Evento> eventosCapitalizados,
			DisposicionCapitalizacion disposicionCapitalizacion)
			throws CuadroEventoException {

		Long idOperacion = operacion.getId();
		EventosOperacion eventosOperacion = new EventosOperacion();
		List<Evento> eventos = new ArrayList<Evento>();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();

		try {
			// inicializa datos necesarios para la regeneracion del
			// cuadro
			fechaInicioPrimerEvento = inicializaCuadroEventos(
					operacion, disposicionCapitalizacion,
					fechaEjecucion, eventosOperacion,
					idsDisposicion, planesEjecucion);

			// Recupera los eventos que afectan al capital pendiente
			// para la
			// precedencia
			helper.getEventosAfectanCPDia(
					operacion, eventos, fechaEjecucion,
					eventosOperacion, false);
			helper
					.getEventosNoAfectanCapitalizacion(
							operacion,
							fechaEjecucion, eventos,
							eventosOperacion);
			eventos.add(operacion.getFormalizacionOperacion());
			// Introduzco en la lista de eventos los anteriores a la
			// fecha de
			// ejecucion para el caso de amortizaciones
			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(operacion,
							fechaEjecucion,
							eventosOperacion);

			if (eventosAnteriores != null
					&& eventosAnteriores.size() > 0) {
				for (int i = 0; i < eventosAnteriores
						.size(); i++) {

					eventosOperacion.addEventosNoCobrados(
							eventosAnteriores.get(
									i));
				}

			}
			// realiza la eliminacion, calculo e inserción de
			// eventos y saldos
			regeneraCuadroEventos(operacion, idsDisposicion,
					planesEjecucion, eventosOperacion,
					eventos, fechaInicioPrimerEvento,
					fechaEjecucion, false, true, false,
					false, false, false, false);

			// Se marca los eventos que han sido capitalizados,
			// mover a la clase
			// CapitalizacionServiceImp
			helper
					.actualizaEventosCapitalizados(
							eventosCapitalizados);

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método de entrada al cuadro de eventos que realiza una regeneración
	 * simulada de una operación desde una fecha dada.
	 *
	 * Este método no realiza cambios en BBDD, los saldos calculados serán
	 * retornados y los eventos se añadirán al contedor recibido como
	 * parametro.
	 *
	 * @param operacion
	 *                operación a simular
	 * @param eventosOperacion
	 *                contenedor de eventos de la operación
	 * @param fechaSimulacion
	 *                fecha de regeneración
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 * @return SaldosTotalesOp SaldosTotalesOp
	 */
	public SaldosTotalesOp regeneraSimulacion(Operacion operacion,
			EventosOperacion eventosOperacion, Date fechaSimulacion)
			throws CuadroEventoException {

		HashMap<Date, List<Cobro>> cobros = null;

		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();

		operacion = helper
				.loadOperacion(operacion.getId());

		// inicializa datos necesarios para la regeneracion del cuadro
		fechaInicioPrimerEvento = inicializaCuadroEventos(operacion,
				operacion.getFormalizacionOperacion(),
				fechaSimulacion, eventosOperacion,
				idsDisposicion, planesEjecucion);

		// recupera los eventos que afectan al capital pendiente para la
		// precedencia
		List<Evento> eventos = helper
				.getEventosAfectanCPDia(operacion,
						new ArrayList<Evento>(),
						fechaSimulacion,
						eventosOperacion, false);

		// Evento formalización nunca tiene cobros, pero si no los
		// inicializamos
		// dará error lazy
		Hibernate.initialize(operacion.getFormalizacionOperacion());
		if (operacion.getFormalizacionOperacion()!=null) {
			operacion.getFormalizacionOperacion().setCobros(new HashSet<CobroEvento>());
			eventos.add(operacion.getFormalizacionOperacion());
		}
		

		// añade los eventos manuales al contenedor de eventos
		for (EventoManual eventoManual : operacion
				.getEventosManuales()) {

			Date fechaEvento = FechaUtils.truncateDate(
					eventoManual.getFechaEvento());
			if (fechaSimulacion.equals(fechaEvento)) {
				eventos.add(eventoManual);
			}
		}

		// Introduzco en la lista de eventos los anteriores a la fecha
		// de
		// ejecucion para el caso de amortizaciones
		List<Evento> eventosAnteriores = helper
				.getEventosManualesAnteriores(operacion,
						fechaSimulacion,
						eventosOperacion);

		if (eventosAnteriores != null && eventosAnteriores.size() > 0) {
			for (int i = 0; i < eventosAnteriores.size(); i++) {

				eventosOperacion.addEventosNoCobrados(
						eventosAnteriores.get(i));
			}

		}

		cobros = new HashMap<Date, List<Cobro>>();
		Cobro cobro = new CobroImp();
		cobro.setAplicacion(
				AplicacionCobrosEnum.APLICACION_AMORTIZACION);
		try {
			cobro.setImporte(new ImporteImp(eventosOperacion
					.getImporteSimulacion()));
		} catch (POJOValidationException e) {
			throw new CuadroEventoException(e);
		}
		cobro.setFechaCobro(fechaSimulacion);
		cobro.setOperacion(operacion);
		List<Cobro> temp = new ArrayList<Cobro>();
		temp.add(cobro);
		cobros.put(fechaSimulacion, temp);
		List<CobroEvento> cobrosEventos = new ArrayList<CobroEvento>();
		SaldosTotalesOp saldos = generaCuadroEventos(operacion,
				idsDisposicion, planesEjecucion,
				eventosOperacion, eventos,
				getIdsEventos(eventos),
				fechaInicioPrimerEvento, fechaSimulacion,
				cobros, true, false, false, false, false,
				fechaSimulacion, cobrosEventos);

		return saldos;
	}

	/**
	 * Método de entrada al cuadro de eventos que realiza una regeneración
	 * simulada de la operación de un plan evento desde una fecha dada, en
	 * función de unos eventos predefinidos que deberán tenerse en cuenta.
	 * Este método no realiza cambios en BBDD, los saldos calculados serán
	 * retornados y los eventos se añadirán al contedor recibido como
	 * parametro.
	 * @param plan
	 *                plan de la operación que se quiere simular
	 * @param eventosOperacion
	 *                contenedor de eventos de la operación
	 * @param eventos
	 *                lista de eventos predefinidos para la simulación
	 * @param fechaSimulacion
	 *                fecha de regeneración
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 * @return SaldosTotalesOp SaldosTotalesOp
	 */
	public SaldosTotalesOp regeneraSimulacion(PlanEvento plan,
			EventosOperacion eventosOperacion, List<Evento> eventos,
			Date fechaSimulacion) throws CuadroEventoException {

		HashMap<Date, List<Cobro>> cobros = null;
		Operacion operacion = plan.getOperacion();

		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();
		planesEjecucion.add(plan);

		Date fechaInicioPrimerEvento = helper
				.getFechaInicioPrimerEvento(operacion.getId(),
						fechaSimulacion);

		cobros = new HashMap<Date, List<Cobro>>();
		List<CobroEvento> cobrosEventos = new ArrayList<CobroEvento>();

		SaldosTotalesOp saldos = generaCuadroEventos(operacion,
				new ArrayList<Long>(), planesEjecucion,
				eventosOperacion, eventos,
				getIdsEventos(eventos),
				fechaInicioPrimerEvento, fechaSimulacion,
				cobros, true, false, false, false, false,
				fechaSimulacion, cobrosEventos);

		return saldos;
	}

	/** MÉTODOS PRIVADOS **/

	/**
	 * Método que regenera los eventos de una operación desde una fecha
	 * dada, indicando si deben mantenerse los eventos que afecan al
	 * capital.
	 *
	 * Este método realiza en primer lugar la regeneración de aval de la
	 * operación si tiene, seguido de la misma operación y de la operación
	 * avalada de esta en caso de tenerla.
	 *
	 * @param operacion
	 *                operación a regenerar
	 * @param evento
	 *                evento que se ha recibido como entrada al cuadro
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 * @param sinCambioCapital
	 *                indica si deben mantenerse los eventos que afectan al
	 *                capital
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algún error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventos(Operacion operacion, Evento evento,
			Date fechaEjecucion, Boolean sinCambioCapital, Boolean isRecalculoAmortizaciones)
			throws CuadroEventoException {

		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				operacion.getId().toString() )) {
			LOG_ICO_62556.info("Inicializa regeneraCuadroEventos");
		}
		
		// deja la fecha de ejecución sin hora
		fechaEjecucion = FechaUtils.truncateDate(fechaEjecucion);

		// Contiene los eventos que se están regenerando y sobre los que
		// se
		// recalcularán los saldos
		EventosOperacion eventosOperacion = new EventosOperacion();

		// carga datos necesarios de la operación
		operacion = helper
				.loadOperacion(operacion.getId());

		// INI_ICO-45786 Error al copiar operaciones debido a que no
		// llega el
		// codigo host en operacion
		if (operacion.getCodigoHost() == null) {
			if (evento.getOperacion().getCodigoHost() != null) {
				operacion.setCodigoHost(evento.getOperacion()
						.getCodigoHost());
			}
		}
		// FIN_ICO-45786 Error al copiar operaciones debido a que no
		// llega el
		// codigo host en operacion

		if (evento != null && evento.getEsEstadoActivo()) {
			// carga datos necesarios del evento
			evento = helper.loadEvento(
					evento.getId(), eventosOperacion,
					operacion, fechaEjecucion);
		}
		
		//Cargar fecha fin Amortizacion Caratula
		helper.obtenerFechaFinAmortizacionCaratula(operacion.getId(), eventosOperacion);

		// regenera el cuadro de la operación
		regeneraCuadroEventos(operacion, eventosOperacion, evento,
				fechaEjecucion, sinCambioCapital, isRecalculoAmortizaciones);
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				operacion.getId().toString() )) {
			LOG_ICO_62556.info("Finaliza regeneraCuadroEventos");
		}
	}

	/**
	 * Método para regenerar el cuadro de eventos al dar de alta una
	 * amortización.
	 * @param operacion Operacion
	 * @param evento Evento
	 * @param fechaEjecucion Date
	 * @param sinCambioCapital Boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	private void regeneraCuadroEventosAmortizacion(Operacion operacion,
			Evento evento, Date fechaEjecucion,
			Boolean sinCambioCapital) throws CuadroEventoException {

		// deja la fecha de ejecucion sin hora
		fechaEjecucion = FechaUtils.truncateDate(fechaEjecucion);

		// Contiene los eventos que se están regenerando y sobre los que
		// se
		// recalcularán los saldos
		EventosOperacion eventosOperacion = new EventosOperacion();

		// carga datos necesarios de la operación
		operacion = helper
				.loadOperacion(operacion.getId());
		if (evento != null && evento.getEsEstadoActivo()) {
			// carga datos necesarios del evento
			evento = helper.loadEvento(
					evento.getId(), eventosOperacion,
					operacion, fechaEjecucion);
		}
		
		helper.obtenerFechaFinAmortizacionCaratula(operacion.getId(), eventosOperacion);

		// regenera el cuadro de la operación
		regeneraCuadroEventosAmortizacion(operacion, eventosOperacion,
				evento, fechaEjecucion, sinCambioCapital);

	}

	/**
	 * Método para regenerar el cuadro de eventos incluyendo
	 * las amortizaciones.
	 * @param o Operacion
	 * @param eventosOperacion EventosOperacion
	 * @param ev Evento
	 * @param fEjec Date
	 * @param sinCambioCapital Boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	private void regeneraCuadroEventosAmortizacion(Operacion o,
			EventosOperacion eventosOperacion, Evento ev,
			Date fEjec, Boolean sinCambioCapital)
			throws CuadroEventoException {

		Long idOperacion = o.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();
		boolean hayQueRegenerarSubsidiosDiaMas = false;

		try {
			// carga datos previos necesarios para la regeneración
			fIniPrimerEv = inicializaCuadroEventos(o, ev, fEjec,
					eventosOperacion, idsDisposicion,
					planes);
			fEjec = helper.getFechaInicioLiqManualIntermedia(
					idOperacion, fEjec);
			List<Evento> evs = new ArrayList<Evento>();

			helper.eliminaCobrosEventos(o, fEjec);

			helper.getEventosAfectanCPDiaAmort(o, evs, fEjec,
					eventosOperacion);
			if (ev instanceof AmortizacionManualImp){
            	helper.getDisposicionesAltaReintegro(o, evs, fEjec, eventosOperacion);
            }
			helper.getAmortAnterioresAltaCobro(o, fEjec,
					eventosOperacion);
			
			//ICO-67866
			helper.obtenerEventosPrepagables(o, fEjec, eventosOperacion, evs);

			helper.getEventosMantenerComisionesManuales(o, evs,
					fEjec, eventosOperacion);

//			eliminarPlanesComision(evs, planes);

			// añade la formalización de la operación a los eventos
			if (o.getFormalizacionOperacion() != null) {
				evs.add(o.getFormalizacionOperacion());
			}

			if (ev != null) {
				evs.add(ev);
				// esto quiere decir que estamos modificando la
				// amortizacion de
				// calendario, por lo que el
				// plan de subsidio debería ejecutarse un día
				// más tarde para que
				// no duplique el que se ha mantenido
				if (ev instanceof AmortizacionAutomatica) {
					hayQueRegenerarSubsidiosDiaMas = true;
				}
			}

			// Introduzco en la lista de eventos los anteriores a la
			// fecha de
			// ejecucion para el caso de amortizaciones
			List<Evento> evAnteriores = helper
					.getEventosManualesAnteriores(o, fEjec,
							eventosOperacion);

			if (isDevolucionFactura(ev)) {
				List<Evento> evLiqDADF = getEventosADFLiqDisposicion(o, evs, fEjec);
				evs = helper.getEventosRegeneracionDisposicion(
						o, fEjec, eventosOperacion, isDevolucionFactura(ev)); //ICO-77191 Añadir isDevolucionFactura
				evs.addAll(evLiqDADF);
				
				
			}

			addAllEventosNoCobrados(evAnteriores, eventosOperacion);

			// ICO-42413 Cuando se recalcula el cuadro de eventos si
			// el plan de
			// amortización es porcentual formalizado
			// se debe calcular el formalizado de nuevo puesto que
			// este valor es
			// el que toma para recalcular el importe de
			// las amortizaciones posteriores a la fecha de
			// recálculo.
			// El importe base para calcular las nuevas
			// amortizaciones debe ser
			// el formalizado - el total de las amortizaciones
			// anteriores a la
			// fecha y la nueva amortización
			
			List<Evento> amos = helper.getAmortizacionesAnteriores(
					o, FechaUtils.sumarDias(fEjec, 1),
					eventosOperacion);
			if (amos != null) {
				calculaNuevoFormalizado(planes, amos);
			}
			 
			
			addEventosAutomaticos(evs, eventosOperacion);


			regeneraCuadroEventos(o, idsDisposicion, planes,
					eventosOperacion, evs, fIniPrimerEv,
					fEjec, false, false,
					hayQueRegenerarSubsidiosDiaMas, false,
					false, false, false);

			if (ev != null && ev.isManual()
					&& !(ev instanceof EventoNoCobrable)
					&& ev.getCobros().isEmpty()) {
				helper.doCobrosEvento(ev, fEjec,
						new ArrayList<CobroEvento>());
			}

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método para recalcular el cuadro de eventos cuando se da de
	 * baja una amortización anticipada.
	 * @param operacion Operacion
	 * @param evento Evento
	 * @param fechaEjecucion Date
	 * @throws CuadroEventoException CuadroEventoException
	 */
	private void regeneraCuadroEventosBajaAva(Operacion operacion,
			Evento evento, Date fechaEjecucion)
			throws CuadroEventoException {

		fechaEjecucion = FechaUtils.truncateDate(fechaEjecucion);
		EventosOperacion eventosOperacion = new EventosOperacion();

		operacion = helper.loadOperacion(operacion.getId());
		if (evento != null && evento.getEsEstadoActivo()) {
			evento = helper.loadEvento(evento.getId(),
					eventosOperacion, operacion,
					fechaEjecucion);
		}
		
		helper.obtenerFechaFinAmortizacionCaratula(operacion.getId(), eventosOperacion);

		regeneraCuadroEventosBajaAva(operacion, eventosOperacion,
				evento, fechaEjecucion);
	}

	/**
	 * Método encargado de regenerar el cuadro de eventos cuando
	 * se da de alta la primera disposición del préstamo (alta
	 * de préstamos de mediación).
	 * @param disposicion DisposicionOperacion
	 * @param variasDisposiciones boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraCuadroEventosAltaPrimeraDisposicion(
			DisposicionOperacion disposicion,
			boolean variasDisposiciones)
			throws CuadroEventoException {

		Date fEjec = FechaUtils
				.truncateDate(disposicion.getFechaEvento());
		EventosOperacion evOp = new EventosOperacion();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fIniPrimerEv = null;
		List<PlanEvento> planes = new ArrayList<PlanEvento>();
		HashMap<Date, List<Cobro>> cobros = null;
		List<Evento> evs = null;
//		Date fGenAmorts = FechaUtils.sumaUnDiaCalendario(fEjec);
		Date fGenAmorts = fEjec;
		List<CobroEvento> cobrosEventos = new ArrayList<CobroEvento>();

		Operacion o = helper.loadOperacion(
				disposicion.getOperacion().getId());
		Long idOperacion = o.getId();

		try {
			cobros = new HashMap<Date, List<Cobro>>();
			evs = new ArrayList<Evento>();

			evs.add(o.getFormalizacionOperacion());
			evs.add(disposicion);

			idsDisposicion.add(disposicion.getId());

			helper.setFechaInterfazContable(idOperacion, fEjec);

			if (variasDisposiciones) {
				// elimina los cobros de eventos
				helper.eliminaCobrosEventos(o, fEjec);

				evs.addAll(helper.getEvVariasDisposiciones(o,
						fEjec, evOp));

				// añade la formalizacion de la operacion a los
				// eventos
				if (o.getFormalizacionOperacion() != null) {
					evs.add(o.getFormalizacionOperacion());
				}
				if (disposicion != null) {
					evs.add(disposicion);
				}

				// Introduzco en la lista de eventos los
				// anteriores a la fecha
				// de ejecucion para el caso de amortizaciones
				List<Evento> eventosAnteriores = helper
						.getEventosManualesAnteriores(o,
								fEjec, evOp);

				addAllEventosNoCobrados(eventosAnteriores,
						evOp);

				addEventosAutomaticos(evs, evOp);

				if (o.getPlanesSubsidio() != null
						&& o.getPlanesSubsidio()
								.size() > 0) {
					helper.eliminaCobrosSubsidios(
							idOperacion, fEjec);
				}

				List<Long> idsEventoMantener = getIdsEventos(
						evs);
				List<Long> idEventos = new ArrayList<Long>();

				// Elimina los saldos correspondientes a los
				// planes que se van a
				// ejecutar
				// Elimina los eventos correspondientes a los
				// planes que se van
				// a ejecutar
				helper.eliminaSaldosEventos(idOperacion, fEjec,
						idsEventoMantener,
						mensajeroMediadorService,
						idEventos, false, o.getTipoOperacionActivo().getCodigo());//ICO-65728

			}

			// carga los saldos necesarios para la regeneración
			SaldosTotalesOp saldos = helper.consultaSaldos(o,
					idsDisposicion, fIniPrimerEv, fEjec,
					false);

			String calRen = o.getCalendarioRenovacion();
			PlanAjustableDias pAjuste = o.getPlanAjustableDias();
			List<Date> festivos = aplicacionDiasHabiles
					.getDiasFesivos(pAjuste, calRen);

			helper.getPlanesEjecucion(o, fEjec, disposicion, evOp,
					planes);

			ejecutarEventos(evs, fEjec, idOperacion, idsDisposicion,
					evOp, saldos, cobros, cobrosEventos, planes);

			optimizacionAltaPrimeraDisposicion(o, planes);
			
			SaldosTotalesOp saldosParaCom = new SaldosTotalesOpImp();
			
			ejecutarPlanesEvento(planes, fEjec, idOperacion,
					idsDisposicion, evOp, saldos, cobros,
					fGenAmorts, festivos, false, false,
					false, fEjec, cobrosEventos, o,saldos, false, saldosParaCom); //ICO-62408

			helper.insertaSaldosEventos(o, saldos,
					evOp.getEventosGenerados(), cobros,
					fEjec, evs, cobrosEventos);

			if (o.getPlanesCalendarioComision() == null
					|| o.getPlanesCalendarioComision()
							.size() > 0) {
				// OPTIMIZACIÓN: Si hay planes de comisión
				// significa que después
				// habrá un cobro puntual. Por lo tanto, como
				// después va a haber
				// otra regeneración del cuadro de eventos, por
				// lo que no merece
				// la pena realizar estas acciones.

				// indica a la operación que su cuadro de
				// eventos está
				// actualizado
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, true);

				eliminarActualizarEventos(o.getId());
			}

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método que elimina planes innecesarios para el alta de
	 * la primera disposición.
	 * @param op Operacion
	 * @param planesEjecucion List<PlanEvento>
	 */
	private void optimizacionAltaPrimeraDisposicion(Operacion op,
			List<PlanEvento> planesEjecucion) {
		if ((op.getPlanesCalendarioComision() == null
				|| op.getPlanesCalendarioComision().size() > 0)
				&& !FormatUtils.isAval(op.getCodigoHost())) {
			// OPTIMIZACIÓN: Si hay planes de comisión
			// significa que después
			// habrá un cobro puntual. Por lo tanto, como
			// después va a haber
			// otra regeneración del cuadro de eventos, no
			// merece la pena
			// generar amortizaciones, demoras o intereses.
			// Únicamente
			// generamos
			// las comisiones. Eliminamos el resto de
			// planes.

			Iterator<PlanEvento> it = planesEjecucion.iterator();
			while (it.hasNext()) {
				PlanEvento p = it.next();
				TipoPlanEventoEnum t = p.getTipoPlanEvento();
				if (isPlanComision(t)) {
					continue;
				} else if (isPlanAmort(t)) {
					continue;
				} else if (isPlanInteresDisposicion(t)) {
					continue;
				} else {
					it.remove();
				}
			}
		}
	}

	/**
	 * Método que regenera los eventos de una operación por una fijación de
	 * tipos desde una fecha dada.
	 *
	 * Este método realiza en primer lugar la regeneración de aval de la
	 * operación si tiene, seguido de la misma operación y de la operación
	 * avalada de esta en caso de ternerla.
	 *
	 * @param operacion
	 *                operación a regenerar
	 * @param evento
	 *                evento que se ha recibido como entrada al cuadro
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventosFijacionTipos(Operacion operacion,
			Evento evento, Date fechaEjecucion, boolean isRecalculoDemoras, boolean isRecalculoIntereses)
			throws CuadroEventoException {

		// deja la fecha de ejecucion sin hora
		fechaEjecucion = FechaUtils.truncateDate(fechaEjecucion);

		// Contiene los eventos que se están regenerando y sobre los que
		// se
		// recalcularán los saldos
		EventosOperacion eventosOperacion = new EventosOperacion();

		// carga datos necesarios de la operacion
		operacion = helper
				.loadOperacion(operacion.getId());
		if (evento != null && evento.getEsEstadoActivo()) {
			// carga datos necesarios del evento
			evento = helper.loadEvento(
					evento.getId(), eventosOperacion,
					operacion, fechaEjecucion);
		}

		// regenera el cuadro de la operacion
		regeneraCuadroEventosFijacionTipos(operacion, eventosOperacion,
				evento, fechaEjecucion, isRecalculoDemoras, isRecalculoIntereses);
	}

	/**
	 * Método que regenera los eventos de una operación desde una fecha
	 * dada, indicando si deben mantenerse los eventos que afecan al
	 * capital.
	 *
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, recibe como parametro un contenedor de eventos ya que en
	 * una misma regeneracion la operación y su operación avalada o aval
	 * deben tener acceso a todos los eventos.
	 *
	 * @param operacion
	 *                operación a regenerar
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param ev
	 *                evento que se ha recibido como entrada al cuadro
	 * @param fEjec
	 *                fecha de regeneración
	 * @param sinCambioCapital
	 *                indica si deben mantenerse los eventos que afecan al
	 *                capital
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventos(Operacion operacion,
			EventosOperacion eventosOperacion, Evento ev,
			Date fEjec, Boolean sinCambioCapital, Boolean isRecalculoAmortizaciones)
			throws CuadroEventoException {
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				operacion.getId().toString() )) {
			LOG_ICO_62556.info("Inicializa regeneraCuadroEventos");
		}

		Long idOperacion = operacion.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();
		boolean hayQueRegenerarSubsidiosDiaMas = false;

		try {
			fechaInicioPrimerEvento = inicializaCuadroEventos(
					operacion, ev, fEjec, eventosOperacion,
					idsDisposicion, planesEjecucion);

			fEjec = helper.getFechaInicioLiqManualIntermedia(
					idOperacion, fEjec);

			List<Evento> evs = new ArrayList<Evento>();

			if (sinCambioCapital) {
				if (hayDisposicionesCapitalizacionPosteriores(
						operacion, fEjec)) {
					helper.getEventosAfectanCPDia(operacion,
							evs, fEjec,
							eventosOperacion, isRecalculoAmortizaciones);
				} else {
					helper.eliminaCobrosEventos(operacion,
							fEjec);
					helper.getEventosMantenerFijacionTipos(
							operacion, evs, fEjec,
							eventosOperacion, false,false);

					borrarPlanAmortizacion(planesEjecucion);
				}
			} else {
				helper.eliminaCobrosEventos(operacion, fEjec);

				helper.getEventosAfectanCPDia(operacion, evs,
						fEjec, eventosOperacion, isRecalculoAmortizaciones);
			}

			helper.getAmortAnterioresAltaCobro(operacion, fEjec,
					eventosOperacion);
			//ICO-102821 Corrige duplicado comisiones por amort. anticipadas
			eliminarPlanesComisionAmoAnticipadaFijo(evs, planesEjecucion); 

			// añade la formalizacion de la operacion a los eventos
			if (operacion.getFormalizacionOperacion() != null) {
				evs.add(operacion.getFormalizacionOperacion());
			}
			if (ev != null) {
				evs.add(ev);
				// esto quiere decir que estamos modificando la
				// amortizacion de
				// calendario, por lo que el
				// plan de subsidio debería ejecutarse un día
				// más tarde para que
				// no duplique el que se ha mantenido
				if (ev instanceof AmortizacionAutomatica) {
					hayQueRegenerarSubsidiosDiaMas = true;
				}
			}

			// Introduzco en la lista de eventos los anteriores a la
			// fecha de
			// ejecucion para el caso de amortizaciones
			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(operacion,
							fEjec,
							eventosOperacion);

			helper.getEventosMantenerComisionesManuales(operacion,
					evs, fEjec, eventosOperacion);

			if (isDevolucionFactura(ev)) {
				evs = helper.getEventosRegeneracionDisposicion(
						operacion, fEjec,
						eventosOperacion,isDevolucionFactura(ev)); //ICO-77191 Añadir isDevolucionFactura
			}

			addAllEventosNoCobrados(eventosAnteriores,
					eventosOperacion);

			// ICO-42413 Cuando se recalcula el cuadro de eventos si
			// el plan de
			// amortización es porcentual formalizado
			// se debe calcular el formalizado de nuevo puesto que
			// este valor es
			// el que toma para recalcular el importe de
			// las amortizaciones posteriores a la fecha de
			// recálculo.
			// El importe base para calcular las nuevas
			// amortizaciones debe ser
			// el formalizado - el total de las amortizaciones
			// anteriores a la
			// fecha
			
			List<Evento> amos = helper.getAmortizacionesAnteriores(
					operacion,
					FechaUtils.sumarDias(fEjec, 1),
					eventosOperacion);
			if (amos != null) {
				calculaNuevoFormalizado(planesEjecucion, amos);
			}
			
			
			addEventosAutomaticos(evs, eventosOperacion);

			regeneraCuadroEventos(operacion, idsDisposicion,
					planesEjecucion, eventosOperacion, evs,
					fechaInicioPrimerEvento, fEjec, false,
					false, hayQueRegenerarSubsidiosDiaMas,
					false, false, false, false);

			// Se cobra los eventos manaules para las operaciones de
			// avales
			if (ev != null && ev.isManual()
					&& !(ev instanceof EventoNoCobrable)
					&& ev.getCobros().isEmpty()) {
				helper.doCobrosEvento(ev, fEjec,
						new ArrayList<CobroEvento>());
			}

			eliminarActualizarEventos(operacion.getId());
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Finaliza regeneraCuadroEventos");
			}

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Añade los eventos automaticos de la lista al set de eventos
	 * no cobrados.
	 * @param eventos List<Evento>
	 * @param eventosOperacion EventosOperacion
	 */
	private void addEventosAutomaticos(List<Evento> eventos,
			EventosOperacion eventosOperacion) {
		if (eventos != null && eventos.size() > 0) {
			for (Evento ev : eventos) {
				if (ev instanceof EventoAutomatico) {
					eventosOperacion.addEventosNoCobrados(
							ev);
				}
			}
		}
	}
	/**
	 * Comprueba si hay disposiciones de capitalización posteriores a
	 * la fecha de ejecución.
	 * @param operacion Operacion
	 * @param fechaEjecucion Date
	 * @return boolean
	 */
	private boolean hayDisposicionesCapitalizacionPosteriores(
			Operacion operacion, Date fechaEjecucion) {
		return helper.hayDisposicionesCapitalizacionPosteriores(
				operacion, fechaEjecucion);
	}

	/**
	 * Regeneración del cuadro de eventos cuando se da de baja
	 * una disposición.
	 * @param disposicion DisposicionOperacion
	 * @throws CuadroEventoException CuadroEventoException
	 */
	public void regeneraCuadroEventosBajaDisposicion(
			DisposicionOperacion disposicion)
			throws CuadroEventoException {

		List<Evento> eventos = new ArrayList<Evento>();
		Operacion operacion = helper.loadOperacion(disposicion.getOperacion().getId());
		Long idOperacion = operacion.getId();
		Date fechaEjecucion = FechaUtils.truncateDate(disposicion.getFechaEvento());
		EventosOperacion eventosOperacion = new EventosOperacion();

		try {
			helper.setFechaInterfazContable(idOperacion, fechaEjecucion);

			helper.eliminaCobrosSubsidios(idOperacion, fechaEjecucion);

			if (operacion.getFormalizacionOperacion() != null) {
				eventos.add(operacion.getFormalizacionOperacion());
			}
			
			eventos.addAll(helper.getEventosRegeneracionDisposicion(operacion, fechaEjecucion, eventosOperacion, contieneEventoManualEELL(disposicion.getOperacion()))); //ICO-77191 Añadir isDevolucionFactura

			List<Long> idsEventoMantener = getIdsEventos(eventos);
			
			//ICO-77928 Eliminar interes disposicion con mantenimiento especial si existe
			Long idIntDisp = this.disposicionService.getIntDisposicionEspecial(idOperacion, disposicion.getId());
			if (idIntDisp != null) {
				idsEventoMantener.remove(idIntDisp);
			}

			idsEventoMantener.remove(disposicion.getId());

			helper.eliminaSaldosEventos(idOperacion, fechaEjecucion, idsEventoMantener, mensajeroMediadorService, new ArrayList<Long>(), false, operacion.getTipoOperacionActivo().getCodigo()); //ICO-65728

			operacionJDBC.actualizarCheckCuadroActualizado(idOperacion, true);

			eliminarActualizarEventos(operacion.getId()); 

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}

	}

	/**
	 * Regeneración del cuadro de eventos cuando se produce
	 * una baja de una amortización anticipada.
	 * @param operacion Operacion
	 * @param evOp EventosOperacion
	 * @param eve Evento
	 * @param fEjec Date
	 * @throws CuadroEventoException CuadroEventoException
	 */
	private void regeneraCuadroEventosBajaAva(Operacion operacion,
			EventosOperacion evOp, Evento eve, Date fEjec)
			throws CuadroEventoException {

		Long idOperacion = operacion.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();
		boolean hayQueRegenerarSubsidiosDiaMas = false;
		List<Evento> eventos = new ArrayList<Evento>();

		

		try {
			fechaInicioPrimerEvento = inicializaCuadroEventos(
					operacion, eve, fEjec, evOp,
					idsDisposicion, planesEjecucion);

			fEjec = helper.getFechaInicioLiqManualIntermedia(
					idOperacion, fEjec);

			// elimina los cobros de eventos
			helper.eliminaCobrosEventos(operacion, fEjec);

			helper.getEventosAfectanCPDiaBajaAva(operacion,
					eventos, fEjec, evOp);

			//ICO-67866
			helper.obtenerEventosPrepagables(operacion, fEjec, evOp, eventos);
			
			helper.getEventosMantenerComisionesManuales(operacion,
					eventos, fEjec, evOp);

			// ICO-56462 - 19/08/2019 - Se comenta la línea inferior para que se recalculen las comisiones
			// eliminarPlanesComision(eventos, planesEjecucion); 

			// añade la formalizacion de la operacion a los eventos
			if (operacion.getFormalizacionOperacion() != null) {
				eventos.add(operacion
						.getFormalizacionOperacion());
			}

			if (eve != null) {
				eventos.add(eve);
				// esto quiere decir que estamos modificando la
				// amortizacion de
				// calendario, por lo que el
				// plan de subsidio debería ejecutarse un día
				// más tarde para que
				// no duplique el que se ha mantenido
				if (eve instanceof AmortizacionAutomatica) {
					hayQueRegenerarSubsidiosDiaMas = true;
				}
			}

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(operacion,
							fEjec, evOp);

			addAllEventosNoCobrados(eventosAnteriores, evOp);

			// ICO-42413 Cuando se recalcula el cuadro de eventos si
			// el plan de
			// amortización es porcentual formalizado
			// se debe calcular el formalizado de nuevo puesto que
			// este valor es
			// el que toma para recalcular el importe de
			// las amortizaciones posteriores a la fecha de
			// recálculo.
			// El importe base para calcular las nuevas
			// amortizaciones debe ser
			// el formalizado - el total de las amortizaciones
			// anteriores a la
			// fecha
			Date fEjecMasUnDia = FechaUtils.sumarDias(fEjec, 1);
			List<Evento> amortizaciones = helper
					.getAmortizacionesAnteriores(operacion,
							fEjecMasUnDia, evOp);
			if (amortizaciones != null) {
				calculaNuevoFormalizado(planesEjecucion,
						amortizaciones);
			}

			if (eventos != null && eventos.size() > 0) {
				for (Evento ev : eventos) {
					if (ev instanceof EventoAutomatico) {
						evOp.addEventosNoCobrados(ev);
					}
				}
			}

			// INI ICO-37489 - 16/06/2015
			// Las devoluciones de factura no deben borrar
			// amortizaciones anticipadas posteriores
			if (operacion instanceof OperacionELImp
					&& isDevolucionFactura(eve)) {

				List<Evento> amorts = null;

				amorts = helper.getEventosManuales(operacion,
						fEjec, evOp);

				if (amorts != null && amorts.size() > 0) {
					for (Evento ev : amorts) {
						if (isAmortManual(ev)) {
							eventos.add(ev);
						}
					}
				}

			}

			regeneraCuadroEventos(operacion, idsDisposicion,
					planesEjecucion, evOp, eventos,
					fechaInicioPrimerEvento, fEjec, false,
					false, hayQueRegenerarSubsidiosDiaMas,
					false, false, false, false);

			if (eve != null && eve.isManual()
					&& !(eve instanceof EventoNoCobrable)
					&& eve.getCobros().isEmpty()) {
				helper.doCobrosEvento(eve, fEjec,
						new ArrayList<CobroEvento>());
			}

			eliminarActualizarEventos(operacion.getId());

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Comprueba si el evento es una amortización manual.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isAmortManual(Evento ev) {
		return ev instanceof AmortizacionManual;
	}

	

	/**
	 * Método que regenera los eventos de una operación por una fijación de
	 * tipos desde una fecha dada.
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, recibe como parametro un contenedor de eventos ya que en
	 * una misma regeneracion la operación y su operación avalada o aval
	 * deben tener acceso a todos los eventos.
	 * Dentro de esta regeneración se mantendran siempre los eventos que no
	 * se vean afectados por las liquidaciones de interes y se excluirá el
	 * plan de amortización y demora.
	 * @param op
	 *                operación a regenerar
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param evento
	 *                evento que se ha recibido como entrada al cuadro
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventosFijacionTipos(Operacion op,
			EventosOperacion eventosOperacion, Evento evento,
			Date fechaEjecucion, boolean isRecalculoDemoras, boolean isRecalculoIntereses) throws CuadroEventoException {

		Long idOperacion = op.getId();
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();
		List<Evento> eventos = new ArrayList<Evento>();

		fechaEjecucion = helper.getFechaInicioLiqManualIntermedia(
				idOperacion, fechaEjecucion);

		try {
			fechaInicioPrimerEvento = inicializaCuadroEventos(op,
					evento, fechaEjecucion,
					eventosOperacion, idsDisposicion,
					planesEjecucion);

			helper.eliminaCobrosEventos(op, fechaEjecucion);

			helper.getEventosMantenerFijacionTipos(op, eventos,
					fechaEjecucion, eventosOperacion, 
					isRecalculoDemoras,isRecalculoIntereses); //ICO-60933

			if (op.getFormalizacionOperacion() != null) {
				eventos.add(op.getFormalizacionOperacion());
			}

			if (evento != null) {
				eventos.add(evento);
			}

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(op,
							fechaEjecucion,
							eventosOperacion);

			addAllEventosNoCobrados(eventosAnteriores,
					eventosOperacion);

			op.setPlanDemora(null);
			
			if(isRecalculoDemoras) { // ICO-60933
				mantenerPlanUnico(planesEjecucion, TipoPlanEventoEnum.PLAN_DEMORAS);
			}
			else if(isRecalculoIntereses){
				// ICO-72927 Elimina todos los planes de eventos menos los de intereses
				mantenerPlanesInteres(planesEjecucion, TipoPlanEventoEnum.PLAN_INTERESES, TipoPlanEventoEnum.PLAN_INTERESES_DISPOSICION, TipoPlanEventoEnum.PLAN_SUBSIDIOS_VPO, TipoPlanEventoEnum.PLAN_SUBSIDIOS_VPO_CA);
			}
			else {
				borrarPlanAmortizacion(planesEjecucion); //ICO-53948 - 14/11/2018
			}

			regeneraCuadroEventos(op, idsDisposicion,
					planesEjecucion, eventosOperacion,
					eventos, fechaInicioPrimerEvento,
					fechaEjecucion, false, false, false,
					false, false, false, false);

			this.eliminarActualizarEventos(idOperacion);

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	// ICO-60933 Elimina todos los planes de eventos menos el pasado por parámetro
	private void mantenerPlanUnico(List<PlanEvento> planesEjecucion, TipoPlanEventoEnum planAMantener) {
		Iterator<PlanEvento> it = planesEjecucion.iterator();
		while(it.hasNext()) {
			if(!it.next().getTipoPlanEvento().equals(planAMantener)) {
				it.remove();
			}
		}
	}
	
	// ICO-72927 Elimina todos los planes de eventos menos los de intereses
	private void mantenerPlanesInteres(List<PlanEvento> planesEjecucion, TipoPlanEventoEnum planAMantener,  TipoPlanEventoEnum planAMantener2, 
			TipoPlanEventoEnum planAMantener3, TipoPlanEventoEnum planAMantener4) {
		Iterator<PlanEvento> it = planesEjecucion.iterator();
		PlanEvento aux;
		while(it.hasNext()) {
			aux=it.next();
			if(!(aux.getTipoPlanEvento().equals(planAMantener) || aux.getTipoPlanEvento().equals(planAMantener2)
					|| aux.getTipoPlanEvento().equals(planAMantener3) || aux.getTipoPlanEvento().equals(planAMantener4))) {
				it.remove();
			}
		}
	}

	/**
	 * Método que regenera los eventos de una operación a partir de un cobro
	 * puntual.
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, recibe como parametro un contenedor de eventos ya que en
	 * una misma regeneracion la operación y su operación avalada o aval
	 * deben tener acceso a todos los eventos.
	 * Dentro de esta regeneración se mantendran siempre los eventos que no
	 * se ven afectados por cobros
	 * @param cobroPuntual
	 *                cobro de la operación a regenerar
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventosCobro(Cobro cobroPuntual,
			EventosOperacion eventosOperacion, boolean isRecalculoComisiones)
			throws CuadroEventoException {
		Operacion operacion = helper.loadOperacion(
				cobroPuntual.getOperacion().getId());

		Long idOperacion = operacion.getId();
		Date fechaEjecucion = FechaUtils.sumaUnDiaCalendario(
				cobroPuntual.getFechaCobro());
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();

		try {
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Inicia regeneraCuadroEventosCobro");
			}

			fechaInicioPrimerEvento = inicializaCuadroEventos(
					operacion,
					operacion.getFormalizacionOperacion(),
					fechaEjecucion, eventosOperacion,
					idsDisposicion, planesEjecucion);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Ha terminado inicializaCuadroEventos");
			}

			helper.eliminaCobrosEventosFromCobro(operacion,
					fechaEjecucion);

			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Ha terminado eliminaCobrosEventosFromCobro");
			}
			
			List<Evento> eventos = new ArrayList<Evento>();
			if (cobroPuntual == null || !cobroPuntual
					.getVariasDisposiciones()) {
				
				if(isRecalculoComisiones) { //ICO-66485
					
					List<Long> idsEventos=helper.getQueryMantenerRestoEventosComision(SubtipoEventoEnum.getListSubtipoComisionesRecalculo(), idOperacion, fechaEjecucion);
					
					for(Long idEvento : idsEventos) {
						Evento evento = helper.loadEvento(idEvento, eventosOperacion, operacion,fechaEjecucion);
						evento.setOperacion(operacion);
						eventos.add(evento);
					}
					
					
				}else {
				eventos = helper.getEventosMantenerAltaCobro(
						operacion, fechaEjecucion,
						eventosOperacion, null); //ICO-58380 Se añade parametro esBajaCobro para solucionar error al borrar, ICO-59300 se quita el parametro esBajaCobro por que interfiere en otras peticiones.
				}
			} else {
				eventos = helper.getEventosVariasDisp(
						operacion, fechaEjecucion,
						eventosOperacion);
			}
			
			//ICO-67866
			helper.obtenerEventosPrepagables(operacion, fechaEjecucion, eventosOperacion, eventos);

			helper.getAmortAnterioresAltaCobro(operacion,
					fechaEjecucion, eventosOperacion);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Ha terminado getAmortAnterioresAltaCobro");
			}

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(operacion,
							fechaEjecucion,
							eventosOperacion);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Ha terminado getEventosManualesAnteriores");
			}

			addAllEventosNoCobrados(eventosAnteriores,
					eventosOperacion);

			addEventosManualesYNoCobrados(eventos, operacion,
					eventosOperacion);

			if(isRecalculoComisiones) { //ICO-66485
				mantenerPlanUnico(planesEjecucion, TipoPlanEventoEnum.PLAN_COMISION);
			}else {
			borrarPlanAmortizacion(planesEjecucion);
			}

			hayLiquidacionesComprometidas(eventos, operacion,
					planesEjecucion, fechaEjecucion,
					eventosOperacion);

			eventosOperacion.setBajaCobro(true);
			
			// INI - ICO-53928 - 17/12/2018
			if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo()) && operacion.getCuadroCosteAmortizado() && cobroPuntual.getImporte().getCantidad().equals(new BigDecimal(0))){
				operacionDAO.recalculoCuadroCosteAmortizado(operacion, fechaEjecucion);
			}
			// FIN - ICO-53928 - 17/12/2018

			regeneraCuadroEventos(operacion, idsDisposicion,
					planesEjecucion, eventosOperacion,
					eventos, fechaInicioPrimerEvento,
					fechaEjecucion, false, false, false,
					false, false, false, true);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Ha terminado regeneraCuadroEventos");
			}

			this.eliminarActualizarEventos(idOperacion);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					idOperacion.toString() )) {
				LOG_ICO_62556.info("Ha terminado eliminarActualizarEventos");
			}

		} catch (Exception e) {
			try {
				LOG.error(e.getMessage());
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Método que regenera los eventos de una operación a partir de un cobro
	 * puntual
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, recibe como parametro un contenedor de eventos ya que en
	 * una misma regeneracion la operación y su operación avalada o aval
	 * deben tener acceso a todos los eventos.
	 * Dentro de esta regeneración se mantendran siempre los eventos que no
	 * se ven afectados por cobros
	 * @param cobroPuntual
	 *                cobro de la operación a regenerar
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param eliminarActualizarEventos boolean
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventosCobroMediacion(Cobro cobroPuntual,
			EventosOperacion eventosOperacion,
			boolean eliminarActualizarEventos)
			throws CuadroEventoException {
		Operacion op = helper.loadOperacion(
				cobroPuntual.getOperacion().getId());

		Long idOperacion = op.getId();
		Date fechaEjecucion = FechaUtils.sumaUnDiaCalendario(
				cobroPuntual.getFechaCobro());
		List<Long> idsDisposicion = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();

		try {
			fechaInicioPrimerEvento = inicializaCuadroEventos(op,
					op.getFormalizacionOperacion(),
					fechaEjecucion, eventosOperacion,
					idsDisposicion, planesEjecucion);

			List<Evento> eventos = new ArrayList<Evento>();
			if (cobroPuntual == null || !cobroPuntual
					.getVariasDisposiciones()) {
				eventos = helper.getEventosMantenerAltaCobro(
						op, fechaEjecucion,
						eventosOperacion, cobroPuntual); //ICO-58380 Se añade parametro esBajaCobro para solucionar error al borrar, ICO-59300 se quita el parametro esBajaCobro por que interfiere en otras peticiones.
			} else {
				eventos = helper.getEventosVariasDisp(op,
						fechaEjecucion,
						eventosOperacion);
			}
			
			//ICO-67866 - 05/10/2021
			helper.obtenerEventosPrepagables(op, fechaEjecucion, eventosOperacion, eventos);

			helper.getAmortAnterioresAltaCobro(op, fechaEjecucion,
					eventosOperacion);

			List<Evento> eventosAnteriores = helper
					.getEventosManualesAnteriores(op,
							fechaEjecucion,
							eventosOperacion);

			addAllEventosNoCobrados(eventosAnteriores,
					eventosOperacion);

			addEventosManuales(eventos, op);

			borrarPlanAmortizacion(planesEjecucion);

			hayLiquidacionesComprometidas(eventos, op,
					planesEjecucion, fechaEjecucion,
					eventosOperacion);

			eventosOperacion.setBajaCobro(true);

			regeneraCuadroEventos(op, idsDisposicion,
					planesEjecucion, eventosOperacion,
					eventos, fechaInicioPrimerEvento,
					fechaEjecucion, false, false, false,
					false, false, false, true);

			if (eliminarActualizarEventos) {
				eliminarActualizarEventos(op.getId());
			}

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}
	

	/**
	 * Añade al set de eventos no cobrados de eventosOperacion
	 * los eventos pasados por parámetro.
	 * @param eventos List<Evento>
	 * @param eventosOperacion EventosOperacion
	 */
	private void addAllEventosNoCobrados(List<Evento> eventos,
			EventosOperacion eventosOperacion) {
		if (eventos != null && eventos.size() > 0) {
			for (int i = 0; i < eventos.size(); i++) {

				eventosOperacion.addEventosNoCobrados(
						eventos.get(i));
			}

		}
	}

	/**
	 * Método para borrar el plan de amortización del set de planes
	 * ya que no suele ser necesario recalcularlo.
	 * @param planesEjecucion List<PlanEvento>
	 */
	private void borrarPlanAmortizacion(List<PlanEvento> planesEjecucion) {
		TipoPlanEventoEnum t = TipoPlanEventoEnum.PLAN_AMORTIZACION;
		Iterator<PlanEvento> it = planesEjecucion.iterator();
		while (it.hasNext()) {
			if (it.next().getTipoPlanEvento().equals(t)) {
				it.remove();
			}
		}
	}
	
	// INI - ICO-60274 - 15/06/2020
	/**
	 * Método para borrar el plan de amortización del set de planes
	 * ya que no suele ser necesario recalcularlo.
	 * @param planesEjecucion List<PlanEvento>
	 */
	private void borrarPlanDemora(List<PlanEvento> planesEjecucion) {
		TipoPlanEventoEnum t = TipoPlanEventoEnum.PLAN_DEMORAS;
		Iterator<PlanEvento> it = planesEjecucion.iterator();
		while (it.hasNext()) {
			if (it.next().getTipoPlanEvento().equals(t)) {
				it.remove();
			}
		}
	}
	// FIN - ICO-60274 - 15/06/2020
	/**
	 * Método para borrar los subsidios del prestamo
	 * es obligatorio regenerarlo
	 * @throws Exception 
	
	 */
	private void borrarSubsidios(Long id, Date fEjec) throws Exception {

			List<Long> operaciones = new ArrayList<>();
			operaciones.add(id);

			eliminarAllEventos(operaciones, fEjec);
		}

		/**
		 * Actualiza el número de evento y elimina los eventos no activos.
		 * @param operaciones List<Long>
		 * @throws Exception Exception
		 */
		public void eliminarAllEventos(List<Long> operaciones, Date fEjec)
				throws Exception {
			

			helper.eliminarEventosSubsidios(operaciones, fEjec);
		}
	
	/**
	 * Método para borrar el plan de interés de disposición en función
	 * de un ID pasado por parámetros ya que no es necesario recalcularlo.
	 * @param planesEjecucion List<PlanEvento>
	 * @param id Long Id de la disposición
	 */
	private void borrarPlanesInteresDisposicion(List<PlanEvento> planesEjecucion, Long id) {
		Iterator<PlanEvento> it = planesEjecucion.iterator();
		while (it.hasNext()) {
			PlanEvento p = it.next();
			if (p.getDisposicion() != null &&
					p.getDisposicion().getId() != null
					&& p.getDisposicion().getId().equals(id)) {
				it.remove();
			}
		}
	}

	/**
	 * Añade los eventos manuales al set de eventos manuales
	 * de la operación.
	 * @param eventos List<Evento>
	 * @param operacion Operacion
	 */
	private void addEventosManuales(List<Evento> eventos,
			Operacion operacion) {
		for (Evento ev : eventos) {
			if (ev.isManual()) {
				operacion.addEventoManualToSet(
						(EventoManual) ev);
			}
		}
	}

	/**
	 * Añade los eventos manuales al set de eventos manuales
	 * de la operación.
	 * @param eventos List<Evento>
	 * @param operacion Operacion
	 * @param eventosOperacion EventosOperacion
	 */
	private void addEventosManualesYNoCobrados(List<Evento> eventos,
			Operacion operacion,
			EventosOperacion eventosOperacion) {
		for (Evento ev : eventos) {
			if (ev.isManual()) {
				operacion.addEventoManualToSet(
						(EventoManual) ev);
				eventosOperacion.addEventosNoCobrados(ev);
			}
		}
	}

	/**
	 * ICO-43298 Si existen LQI comprometidas futuras no recalculamos los
	 * intereses con un cobro.
	 * @param evs List<Evento>
	 * @param operacion Operacion
	 * @param planesEjecucion List<PlanEvento>
	 * @param fechaEjecucion Date
	 * @param evOperacion EventosOperacion
	 */
	private void hayLiquidacionesComprometidas(List<Evento> evs,
			Operacion operacion, List<PlanEvento> planesEjecucion,
			Date fechaEjecucion, EventosOperacion evOperacion) {
		TipoOperacionActivoEnum fad = TipoOperacionActivoEnum.FF;
		TipoPlanEventoEnum tInt = TipoPlanEventoEnum.PLAN_INTERESES;
		for (Evento ev : evs) {
			TipoEventoEnum t = ev.getTipoEvento();
			TipoOperacionActivoEnum to = operacion
					.getTipoOperacionActivo();
			if (t.equals(TipoEventoEnum.LIQUIDACION_INTERESES)
					&& ev.getCondonado()
					&& to.equals(fad)) {
				Iterator<PlanEvento> it2 = planesEjecucion
						.iterator();
				while (it2.hasNext()) {
					PlanEvento plan = it2.next();
					TipoPlanEventoEnum tP = plan
							.getTipoPlanEvento();
					if (tP.equals(tInt)) {
						it2.remove();
						addRestoLiqs(evs, operacion,
								fechaEjecucion,
								evOperacion);
					}
				}
				break;
			}
		}
	}

	/**
	 * Añade el resto de liquidaciones de interés al set de eventos.
	 * @param eventos List<Evento>
	 * @param operacion Operacion
	 * @param fechaEjecucion Date
	 * @param eventosOperacion EventosOperacion
	 */
	private void addRestoLiqs(List<Evento> eventos, Operacion operacion,
			Date fechaEjecucion,
			EventosOperacion eventosOperacion) {
		List<Evento> restoLQI = helper.getRestoLQIs(operacion,
				fechaEjecucion, eventosOperacion);
		eventos.addAll(restoLQI);
	}

	/**
	 * Regenera el cuadro sin crear ningún evento.
	 * @param cobroPuntual Cobro
	 * @param eventosOperacion EventosOperacion
	 * @param eliminarActualizarEventos boolean
	 * @throws CuadroEventoException CuadroEventoException
	 */
	private void regeneraCuadroEventosCobroSubsidio(Cobro cobroPuntual,
			EventosOperacion eventosOperacion,
			boolean eliminarActualizarEventos)
			throws CuadroEventoException {
		Long id = cobroPuntual.getOperacion().getId();

		try {

			eventosDAO.executeProcedureSaldos(
					helper.getCodigoHostByOperacion(id));

			helper.setFechaInterfazContable(id,
					cobroPuntual.getFechaCobro());

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						id, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Actualiza el número de evento y elimina los eventos no activos.
	 * @param idOperacion Long
	 * @throws Exception Exception
	 */
	private void eliminarActualizarEventos(Long idOperacion)
			throws Exception {
		List<Long> operaciones = new ArrayList<Long>();
		operaciones.add(idOperacion);

		eliminarActualizarAllEventos(operaciones);
	}

	/**
	 * Actualiza el número de evento y elimina los eventos no activos.
	 * @param operaciones List<Long>
	 * @throws Exception Exception
	 */
	public void eliminarActualizarAllEventos(List<Long> operaciones)
			throws Exception {
		helper.updateEventosNumEvento(operaciones);

		helper.eliminarEventos(operaciones);
	}

	/**
	 * Método que regenera de una operación desde una fecha dada manteniendo
	 * los eventos manuales
	 * Este Método modifica la operación en BBDD para reflejar los cálculos
	 * obtenidos, recibe como parametro un contenedor de eventos ya que en
	 * una misma regeneracion la operación y su operación avalada o aval
	 * deben tener acceso a todos los eventos.
	 *
	 * Dentro de esta regeneración se mantendran los eventos manuales
	 *
	 * @param operacion
	 *                operación a regenerar
	 * @param fEjec
	 *                fecha de regeneración
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param crearCobrosSubsidiosDiaDespues boolean
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 regeneración
	 */
	private void regeneraCuadroEventosSinBorradoEM(Operacion operacion,
			Date fEjec, EventosOperacion eventosOperacion,
			Boolean crearCobrosSubsidiosDiaDespues) //ICO-58380 Se añade parametro esBajaCobro para solucionar error al borrar, ICO-59300 se quita el parametro esBajaCobro por que interfiere en otras peticiones.
			throws CuadroEventoException {
		operacion = helper.loadOperacion(operacion.getId());
		Long idOperacion = operacion.getId();
		List<Long> idsDisp = new ArrayList<Long>();
		Date fechaInicioPrimerEvento = null;
		List<PlanEvento> planesEjecucion = new ArrayList<PlanEvento>();

		try {
			fechaInicioPrimerEvento = inicializaCuadroEventos(
					operacion,
					operacion.getFormalizacionOperacion(),
					fEjec, eventosOperacion, idsDisp,
					planesEjecucion);

			helper.eliminaCobrosEventos(operacion, fEjec);

			List<Evento> eventos = helper
					.getEventosMantenerAltaCobro(operacion,
							fEjec,
							eventosOperacion, null); //ICO-58380 Se añade parametro esBajaCobro para solucionar error al borrar, ICO-59300 se quita el parametro esBajaCobro por que interfiere en otras peticiones.
			
			//ICO-67866
			helper.obtenerEventosPrepagables(operacion, fEjec, eventosOperacion, eventos);

			helper.getAmortAnterioresAltaCobro(operacion, fEjec,
					eventosOperacion);

			helper.getEventosAfectanCPDia(operacion, eventos,
					fEjec, eventosOperacion, false);

			if (operacion.getFormalizacionOperacion() != null) {
				eventos.add(operacion
						.getFormalizacionOperacion());
			}

			List<Evento> evsAnteriores = helper
					.getEventosManualesAnteriores(operacion,
							fEjec,
							eventosOperacion);
			
			//meter consulta para los cobros de cc y subsidios MyA

			if (evsAnteriores != null && evsAnteriores.size() > 0) {
				for (int i = 0; i < evsAnteriores.size(); i++) {

					eventosOperacion.addEventosNoCobrados(
							evsAnteriores.get(i));
				}

			}

			for (Evento ev : eventos) {
				eventosOperacion.addEventosNoCobrados(ev);
			}

			eliminarPlanesInnecesarios(planesEjecucion, fEjec);

			regeneraCuadroEventos(operacion, idsDisp,
					planesEjecucion, eventosOperacion,
					eventos, fechaInicioPrimerEvento, fEjec,
					false, false, false, false, false,
					crearCobrosSubsidiosDiaDespues, true);

			eliminarActualizarEventos(operacion.getId());

		} catch (Exception e) {
			try {
				operacionJDBC.actualizarCheckCuadroActualizado(
						idOperacion, false);
			} catch (Exception e1) {
				LOG.error(e1);
			}
			throw new CuadroEventoException(e);
		}
	}

	/**
	 * Si el plan de comisión está relacionado con una
	 * amortización, eliminamos del Set los que tengan fechaanterior a la
	 * de la Ejecución. Este cambio se hace porque al guardar el cobro de
	 * una comisión (siendo esta la última fecha de una operación),
	 * duplicaba el saldo de la misma.
	 * @param planes List<PlanEvento>
	 * @param fEjec Date
	 */
	private void eliminarPlanesInnecesarios(List<PlanEvento> planes,
			Date fEjec) {
		Iterator<PlanEvento> it = planes.iterator();
		while (it.hasNext()) {
			PlanEvento p = it.next();
			if (p.getTipoPlanEvento().equals(
					TipoPlanEventoEnum.PLAN_AMORTIZACION)) {
				it.remove();
				continue;
			}

			if (p instanceof PlanCalendarioComisionImp) {
				AmortizacionManual a = null;
				a = ((PlanCalendarioComisionImp) p)
						.getAmortizacion();
				if (a != null && !a.getFechaEvento()
						.after(fEjec)) {
					it.remove();
					continue;
				}
			}
		}
	}

	/**
	 * Método que carga datos iniciales necesarios para la regeneración del
	 * cuadro.
	 * @param operacion
	 *                operacion a regenerar
	 * @param eventoPlanesEvento
	 *                evento que realiza la llamada a la regeneracion
	 * @param fechaEjecucion
	 *                fecha de regeneración
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param idsDisposicion
	 *                lista de ids de la operación
	 * @param planesEjecucion
	 *                listado de planes evento que se ejecutarán
	 *
	 * @return fechaInicioPrimerEvento fecha del primer evento que se debe
	 *         generar
	 */
	private Date inicializaCuadroEventos(Operacion operacion,
			Evento eventoPlanesEvento, Date fechaEjecucion,
			EventosOperacion eventosOperacion,
			List<Long> idsDisposicion,
			List<PlanEvento> planesEjecucion) {
		
		helper.getIdsDisposicion(operacion.getId(), fechaEjecucion,
				idsDisposicion);
		helper.loadLiquidacionesAnteriores(operacion,
				fechaEjecucion, eventosOperacion);

		helper.getPlanesEjecucion(operacion, fechaEjecucion,
				eventoPlanesEvento, eventosOperacion,
				planesEjecucion);

		helper.loadPeriodosCarencia(operacion.getId(),
				eventosOperacion);

		return helper.getFechaInicioPrimerEvento(
				operacion.getId(), fechaEjecucion);
	}

	/**
	 * Inicializa los datos iniciales para recalcular el cuadro
	 * de eventos y devuelve la fecha del primer evento
	 * que haya en saldos.
	 * @param operacion Operacion
	 * @param eventoPlanesEvento Evento
	 * @param fechaEjecucion Date
	 * @param eventosOperacion EventosOperacion
	 * @param idsDisposicion List<Long>
	 * @param planesEjecucion List<PlanEvento>
	 * @return Date
	 */
	private Date inicializaCuadroEventosSaldos(Operacion operacion,
			Evento eventoPlanesEvento, Date fechaEjecucion,
			EventosOperacion eventosOperacion,
			List<Long> idsDisposicion,
			List<PlanEvento> planesEjecucion) {
		helper.getIdsDisposicion(operacion.getId(), fechaEjecucion,
				idsDisposicion);
		helper.loadLiquidacionesAnteriores(operacion,
				fechaEjecucion, eventosOperacion);

		helper.getPlanesEjecucion(operacion, fechaEjecucion,
				eventoPlanesEvento, eventosOperacion,
				planesEjecucion);

		return helper.getFechaInicioPrimerEventoSaldos(
				operacion.getId(), fechaEjecucion);
	}

	/**
	 * Método que realiza la eliminacion, calculo y posterior insercion de
	 * los saldos y eventos en funcion de los parametros de entrada.
	 * @param operacion
	 *                operacion a regenerar
	 * @param idsDisposicion
	 *                lista de ids de la operación
	 * @param planesEjecucion
	 *                listado de planes evento que se ejecutarán
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param eventos
	 *                lista de eventos que no han sido eliminados
	 * @param fechaInicioPrimerEvento
	 *                fecha del primer evento que se debe generar
	 * @param fechaEjecucion
	 *                fecha a la que se ejecutan los planes
	 * @param simulacion
	 *                indica si la generacion es por simulacion
	 * @param capitalizacion
	 *                indica si la generacion es por capitalizacion
	 * @param hayQueRegenerarSubsidiosDiaMas
	 *                indica si hay que generar subsidio a un dia mas
	 * @param cargarSaldosPosteriores
	 *                indica si hay que cargar saldos posteriores a la fecha
	 *                de ejecucion
	 * @param soloDemoras
	 *                indica si si solo debe generar demoras
	 * @param crearCobrosSubsidiosDiaDespues boolean
	 * @param isFromCobro boolean
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 generación
	 */
	private void regeneraCuadroEventos(Operacion operacion,
			List<Long> idsDisposicion,
			List<PlanEvento> planesEjecucion,
			EventosOperacion eventosOperacion, List<Evento> eventos,
			Date fechaInicioPrimerEvento, Date fechaEjecucion,
			boolean simulacion, boolean capitalizacion,
			boolean hayQueRegenerarSubsidiosDiaMas,
			boolean cargarSaldosPosteriores, boolean soloDemoras,
			boolean crearCobrosSubsidiosDiaDespues,
			boolean isFromCobro) throws CuadroEventoException {

		Long idOperacion = operacion.getId();
		List<Long> idEventos = new ArrayList<Long>();
		HashMap<Date, List<Cobro>> cobros = null;
		List<CobroEvento> cobrosEventos = null;
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				operacion.getId().toString() )) {
			LOG_ICO_62556.info("Inicia regenera cuadro eventos");
		}
		
		Collections.sort(eventos, new EventoFechaEventoComparator());//ICO-53341 - 31/10/2018
	
		try {
			
			// INI - ICO-53928 - 17/12/2018
			if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo()) && operacion.getCuadroCosteAmortizado() && !isFromCobro){
				operacionDAO.recalculoCuadroCosteAmortizado(operacion, fechaEjecucion);
				//INI ICO-73987
				if (operacion.getImporteFormalizado().compareTo(operacion.getImporteTotalFormalizado()) > 0 && Calendar.getInstance().getTime().before(calcularFechaPeriodoGracia(operacion.getFechaLimiteDisponibilidad(), operacion.getPeriodoGraciaDisposicion()))) {
					operacionDAO.recalculoCuadroCosteAmortizadoCasoDos(calcularFechaPeriodoGracia(operacion.getFechaLimiteDisponibilidad(), operacion.getPeriodoGraciaDisposicion()), operacion.getCodigoHost());
				}
				//FIN ICO-73987
				
			}
			// FIN - ICO-53928 - 17/12/2018
			
			// obtiene la lista de ids de los eventos que no serán
			// eliminados
			List<Long> idsEventoMantener = getIdsEventos(
					eventos);
			//ICO-102821 Corrige duplicado comisiones por amort. anticipadas
			eliminarPlanesComisionAmoAnticipadaFijo(eventos, planesEjecucion);

			helper.setFechaInterfazContable(
					idOperacion, fechaEjecucion);

			if (operacion.getPlanesSubsidio() != null && operacion
					.getPlanesSubsidio().size() > 0) {
				helper
						.eliminaCobrosSubsidios(
								idOperacion,
								fechaEjecucion);
			}

			helper.eliminaSaldosEventos(
					idOperacion, fechaEjecucion,
					idsEventoMantener,
					mensajeroMediadorService, idEventos,
					isFromCobro, cargarSaldosPosteriores,
					operacion.getTipoOperacionActivo().getCodigo());//ICO-65728

			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha escogido los eventos que no eliminará. Ha eliminado el resto de eventos y los saldos.");
			}

			DisposicionOperacion disposicion = null;
			if (!idsDisposicion.isEmpty()) {
				disposicion = this.disposicionService
						.getDisposicion(idsDisposicion
								.get(0));
				if (disposicion instanceof DisposicionNormal) {
					interfazNucleoDAO.generaMovimientosNB(
							idEventos);
				}
			} else {
				// informa a nucleo de los movimientos de
				// eventos
				interfazNucleoDAO
						.generaMovimientosNB(idEventos);
			}
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha obtenido la primera disposición junto con sus planes de eventos asociados y ha generado los movimientos de saldos.");
			}

			// inserta los eventos y saldos generados en BBDD
			Date fechaDesdeCobroSubsidio = fechaEjecucion;
			if (crearCobrosSubsidiosDiaDespues) {
				fechaDesdeCobroSubsidio = FechaUtils
						.sumaUnDiaCalendario(
								fechaEjecucion);
			}

			cobrosEventos = new ArrayList<CobroEvento>();
			cobros = new HashMap<Date, List<Cobro>>();

			SaldosTotalesOp saldos = generaCuadroEventos(operacion,
					idsDisposicion, planesEjecucion,
					eventosOperacion, eventos,
					idsEventoMantener,
					fechaInicioPrimerEvento, fechaEjecucion,
					cobros, simulacion, capitalizacion,
					hayQueRegenerarSubsidiosDiaMas,
					cargarSaldosPosteriores, soloDemoras,
					fechaDesdeCobroSubsidio, cobrosEventos);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha terminado de generar cuadro eventos.");
			}
			
			helper.insertaSaldosEventos(
					operacion, saldos,
					eventosOperacion.getEventosGenerados(),
					cobros, fechaDesdeCobroSubsidio,
					eventos, cobrosEventos);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha terminado inserta saldo eventos.");
			}

			// indica a la operación que su cuadro de eventos está
			// actualizado
			operacionJDBC.actualizarCheckCuadroActualizado(
					idOperacion, true);

			eliminarActualizarEventos(idOperacion);

		} catch (CuadroEventoException e) {
			throw e;
		} catch (Exception e) {
			throw new CuadroEventoException(e);
		}
	}
	//INI ICO-73987

	private Date calcularFechaPeriodoGracia(Date fechaLimiteDisponibilidad, Integer periodoGraciaDisposicion) {
		Calendar calendar = Calendar.getInstance();
	    calendar.setTime(fechaLimiteDisponibilidad); 
	    calendar.add(Calendar.DAY_OF_YEAR, periodoGraciaDisposicion);
		return calendar.getTime();
		
	}
	//FIN ICO-73987

	/**
	 * Elimina los planes de comisión que no hay que volver a
	 * ejecutar.
	 * @param eventos List<Evento>
	 * @param planes List<PlanEvento>
	 */
	private void eliminarPlanesComisionAmoAnticipadaFijo(List<Evento> eventos,
			List<PlanEvento> planes) {
		//ICO-105688 Evita eliminar todo plan comision automatico, solo alguno asociado para evitar duplicidades
		for (Evento ev : eventos) {
			if(ev instanceof AmortizacionAnticipadaVoluntariaImp) {
				for (Evento ev2 : ev.getEventosDependientes()) {
					if (isLiqComision(ev2)) {
						LiquidacionComisiones liq = null;
						liq = (LiquidacionComisiones) ev2;
	
						if (isPlanCalendarioComision(liq)) {
							borrarPlanComision(planes, liq, ev);
						}
					}
				}
			}
		}
	}

	/**
	 * Borra los planes de comisión si es necesario del set de
	 * planes de ejecución.
	 * @param planes List<PlanEvento>
	 * @param liq LiquidacionComisiones
	 */
	private void borrarPlanComision(List<PlanEvento> planes,
			LiquidacionComisiones liq, Evento ev) {
		Iterator<PlanEvento> it = planes.iterator();
		while (it.hasNext()) {
			PlanEvento plan = (PlanEvento) it.next();
			//ICO-105688 Evita eliminar todo plan comision automatico, solo alguno asociado para evitar duplicidades
			if (plan.getId().equals(liq.getPlanEvento().getId()) && ev.getId().equals(plan.getAmortizacion().getId())) {
				it.remove();
			}
		}
	}

	/**
	 * Comprueba si el eventos es de tipo comisión.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isLiqComision(Evento ev) {
		return ev.getTipoEvento().getCodigo()
				.equals(TipoEventoEnum.LIQUIDACION_COMISION
						.getCodigo());
	}

	/**
	 * Comprueba si el plan de la comisión es de calendario.
	 * @param e LiquidacionComisiones
	 * @return boolean
	 */
	private boolean isPlanCalendarioComision(LiquidacionComisiones e) {
		PlanEvento p = e.getPlanEvento();
		return isPlanCalendarioComisionAsociado(p)
				|| isPlanCalendarioComisionPeriodico(p);
	}

	/**
	 * Comprueba si el tipo del plan es del tipo
	 * PlanCalendarioComisionAsociado.
	 * @param p PlanEvento
	 * @return boolean
	 */
	private boolean isPlanCalendarioComisionAsociado(PlanEvento p) {
		return p instanceof PlanCalendarioComisionAsociado;
	}

	/**
	 * Comprueba si el tipo del plan es del tipo
	 * PlanCalendarioComisionPeriodico.
	 * @param p PlanEvento
	 * @return boolean
	 */
	private boolean isPlanCalendarioComisionPeriodico(PlanEvento p) {
		return p instanceof PlanCalendarioComisionPeriodico;
	}

	/**
	 * Método que realiza la generacion de eventos y saldos en funcion de
	 * los eventos y planes recibidos como parametro.
	 * @param operacion
	 *                operacion a regenerar
	 * @param idsDisposicion
	 *                lista de ids de la operación
	 * @param planesEjecucion
	 *                listado de planes evento que se ejecutarán
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param eventos
	 *                lista de eventos que no han sido eliminados
	 * @param fechaInicioPrimerEvento
	 *                fecha del primer evento que se debe generar
	 * @param fEjec
	 *                fecha a la que se ejecutan los planes
	 * @param cobros
	 *                colecion de cobros de la operación
	 * @param simulacion
	 *                indica si la generacion es por simulacion
	 * @param capitalizacion
	 *                indica si la generacion es por capitalizacion
	 * @param hayQueRegenerarSubsidiosDiaMas
	 *                indica si hay que generar subsidio a un dia mas
	 * @param cargarSaldosPosteriores
	 *                indica si hay que cargar saldos posteriores a la fecha
	 *                de ejecucion
	 * @param soloDemoras
	 *                indica si si solo debe generar demoras
	 * @param fechaDesdeCobroSubsidio Date
	 * @param cobrosEventos List<CobroEvento>
	 * @param idsEventoMantener List<Long>
	 *
	 * @return SaldosTotalesOp saldos actualizados de la operacion
	 *
	 * @throws CuadroEventoException
	 *                 en caso de que ocurra algun error durante la
	 *                 generación
	 */
	private SaldosTotalesOp generaCuadroEventos(Operacion operacion,
			List<Long> idsDisposicion,
			List<PlanEvento> planesEjecucion,
			EventosOperacion eventosOperacion, List<Evento> eventos,
			List<Long> idsEventoMantener,
			Date fechaInicioPrimerEvento, Date fEjec,
			HashMap<Date, List<Cobro>> cobros, boolean simulacion,
			boolean capitalizacion,
			boolean hayQueRegenerarSubsidiosDiaMas,
			boolean cargarSaldosPosteriores, boolean soloDemoras,
			Date fechaDesdeCobroSubsidio,
			List<CobroEvento> cobrosEventos)
			throws CuadroEventoException {

		Long idOperacion = operacion.getId();

		Date fecGenAmort = fEjec;

		try {
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Inicia genera Cuadro Eventos");
			}
			
//			fecGenAmort = 
			inicializarParametros(eventos, idsDisposicion, fecGenAmort, fEjec);

			// carga los saldos necesarios para la regeneración
			SaldosTotalesOp saldos = helper.consultaSaldos(
					operacion, idsDisposicion,
					fechaInicioPrimerEvento, fEjec,
					cargarSaldosPosteriores);
			
			
			boolean tieneCuotaSig=false; 

			for(Evento e : eventosOperacion.getAmortizaciones()){
				if(e.isManual() && e.getReajustePorCuotas() != null && e.getReajustePorCuotas()==2){
			 		tieneCuotaSig=true;
					break;
				}
			}

			// Carga los cobros y los saldos excesos
			List<SaldosOp> saldosExceso;
			try {
				saldosExceso = helper.undoCobros(fEjec,
						operacion, cobros,
						idsEventoMantener, soloDemoras,
						simulacion);
			} catch (Exception e) {
				throw new CuadroEventoException(e);
			}

			Map<Date, List<Cobro>> listaCobros = null;
			listaCobros = new TreeMap<Date, List<Cobro>>(cobros);

			saldos.mezclarSaldosExceso(saldosExceso);
			
			
			PlanAjustableDias pAjuste = operacion
					.getPlanAjustableDias();
			String calRen = operacion.getCalendarioRenovacion();
			List<Date> festivos = aplicacionDiasHabiles
					.getDiasFesivos(pAjuste, calRen);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha obtenido los días festivos");
			}

			if (eventos != null && eventos.size() != 0) {

//				fecGenAmort = FechaUtils.sumaUnDiaCalendario(
//						fecGenAmort);

				addNoCobrados(eventos, eventosOperacion);
			}

			ejecutarCobrosEventos(saldos, listaCobros,idsDisposicion, fEjec); // ICO-118218
			

			// Se realizan los saldos de los eventos futuros que no
			// van a
			// regenerarse
			ejecutarEventos(eventos, fEjec, idOperacion,
					idsDisposicion, eventosOperacion,
					saldos, listaCobros, cobrosEventos, planesEjecucion);
			
			
			
			
			if(!eventosOperacion.getEventosNoCobrados().isEmpty() && listaCobros.size() > 0 && cobrosEventos.isEmpty()) {
				for (Map.Entry<Date, List<Cobro>> cobrosfechaiterador : listaCobros.entrySet()) {
					reajustarExceso(saldos,cobrosfechaiterador.getKey(),eventosOperacion,cobros,fEjec,idsDisposicion,cobrosEventos);
				}
			}
						
			SaldosTotalesOp saldosAux = obtenerSaldoAux(planesEjecucion, operacion, idsDisposicion, fEjec, 
					cargarSaldosPosteriores, idsEventoMantener, soloDemoras, simulacion, eventos, 
					eventosOperacion);
			
			SaldosTotalesOp saldosParaCom = new SaldosTotalesOpImp();
				//ICO-62408 carga todos los saldos para las comisiones no disponibilidad.
			saldosParaCom = helper.consultaSaldos(
					operacion, idsDisposicion,
					null, null,
					true);
			
			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha terminado de cargar y ejecutar todos los cobros y saldos excesos");
			}

			if(saldosParaCom != null && saldosParaCom.getSaldosOperacion() != null && !saldosParaCom.getSaldosOperacion().isEmpty()) {
				ejecutarCobrosEventos(saldosParaCom, listaCobros,idsDisposicion,fEjec); // ICO-118218
			}
			
			ejecutarEventos(eventos, fEjec, idOperacion,
					idsDisposicion, eventosOperacion,
					saldosParaCom, listaCobros, cobrosEventos, planesEjecucion);
			if(!eventosOperacion.getEventosNoCobrados().isEmpty() && listaCobros.size() > 0 && cobrosEventos.isEmpty()) {
				for (Map.Entry<Date, List<Cobro>> cobrosfechaiterador : listaCobros.entrySet()) {
					reajustarExceso(saldosParaCom,cobrosfechaiterador.getKey(),eventosOperacion,cobros,fEjec,idsDisposicion,cobrosEventos);
				}
			}
			

			// Ejecuta los planes de eventos
			eventosOperacion.setDemorasAnteriores(helper
					.demorasAnteriores(operacion, fEjec));

			// buscamos entre los eventos para ver si existe una AVA
			boolean existeAva = existeAnticipada(eventos);

			// sino existe una AVA borramos de los planesEjecucion
			// el plan de
			// comision de AVA
			if (!existeAva) {
				borrarPlanesInnecesarios(planesEjecucion);
			}

			ejecutarPlanesEvento(planesEjecucion, fEjec,
					idOperacion, idsDisposicion,
					eventosOperacion, saldos, listaCobros,
					fecGenAmort, festivos,
					simulacion, capitalizacion,
					hayQueRegenerarSubsidiosDiaMas,
					fechaDesdeCobroSubsidio, cobrosEventos,
					operacion,saldosAux,tieneCuotaSig, saldosParaCom);

			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha terminado ejecutarPlanesEvento");
			}

			this.eliminarActualizarEventos(idOperacion);
			

			if(LogSingletonICO62556.actualizarTrazaICO62556(
					null,
					null,
					operacion.getId().toString() )) {
				LOG_ICO_62556.info("Ha terminado eliminar Actualizar eventos");
			}

			return saldos;

		} catch (Exception e) {
			throw new CuadroEventoException(e);
		}
	}
	
	public void actualizaImporteCuotaCliente(List<Evento> listaIntereses, List<Evento> listaSubsidios) {
		
		for(Evento liquidacion:listaIntereses){
        	for(Evento subsidio : listaSubsidios){
        		if(liquidacion.getFechaEvento().compareTo(subsidio.getFechaEvento()) == 0) {
        			BigDecimal importe = liquidacion.getImporteCuotaCliente().getCantidad().subtract(subsidio.getImporte().getCantidad());
        			liquidacion.getImporteCuotaCliente().setCantidad(importe);
        		}
        	}
        }
	}
	
	/**
	 * Genera una variable auxiliar de saldos para calculo de amortizaciones
	 * @param planesEjecucion
	 * @param operacion
	 * @param idsDisposicion
	 * @param fEjec
	 * @param cargarSaldosPosteriores
	 * @param idsEventoMantener
	 * @param soloDemoras
	 * @param simulacion
	 * @param eventos
	 * @param eventosOperacion
	 * @return
	 * @throws Exception
	 */
	protected SaldosTotalesOp obtenerSaldoAux(List<PlanEvento> planesEjecucion, Operacion operacion, List<Long> idsDisposicion, Date fEjec, 
			boolean cargarSaldosPosteriores, List<Long> idsEventoMantener, boolean soloDemoras, boolean simulacion, List<Evento> eventos, 
			EventosOperacion eventosOperacion) throws Exception {
		SaldosTotalesOp saldo = null;
		Date fechaInicioPrimerEventoAmor = null;
		List<SaldosOp> saldosExceso;
		HashMap<Date, List<Cobro>> cobrosAux = new HashMap<>();
		Map<Date, List<Cobro>> listaCobrosAux = null;
		List<CobroEvento> cobrosEventosAux = new ArrayList<>();
		
		if(contienePlanAmortizacionOperacionBase(planesEjecucion)) {
			fechaInicioPrimerEventoAmor = helper.getFechaInicioPrimerEventoSaldosAmort(operacion.getId());
			
			saldo = helper.consultaSaldos(
					operacion, idsDisposicion,
					fechaInicioPrimerEventoAmor, fEjec,
					cargarSaldosPosteriores);
			
			saldosExceso = helper.undoCobros(fEjec,
					operacion, cobrosAux,
					idsEventoMantener, soloDemoras,
					simulacion);
			saldo.mezclarSaldosExceso(saldosExceso);
			
			listaCobrosAux = new TreeMap<>(cobrosAux);
			ejecutarCobrosEventos(saldo, listaCobrosAux,idsDisposicion,fEjec);
			
			ejecutarEventos(eventos, fEjec, operacion.getId(),
					idsDisposicion, eventosOperacion,
					saldo, listaCobrosAux, cobrosEventosAux, planesEjecucion);
			
			if(!eventosOperacion.getEventosNoCobrados().isEmpty() && listaCobrosAux.size() > 0 && cobrosEventosAux.isEmpty()) {
				for (Map.Entry<Date, List<Cobro>> cobrosfechaiterador : listaCobrosAux.entrySet()) {
					reajustarExceso(saldo,cobrosfechaiterador.getKey(),eventosOperacion,cobrosAux,fEjec,idsDisposicion,cobrosEventosAux);
				}
			}
		}
		
		return saldo;
	}

	/**
	 * Inicializa la lista de idsDisposicion y la fecha de generación
	 * de amortizaciones.
	 * @param eventos List<Evento>
	 * @param idsDisposicion List<Long>
	 * @param fecGenAmort Date
	 * @param fEjec Date
	 * @return fecGenAmort fechaGeneracionAmortizacion
	 */
	private Date inicializarParametros(List<Evento> eventos,
			List<Long> idsDisposicion, Date fecGenAmort,
			Date fEjec) {
		if (eventos != null) {
			for (Evento evento : eventos) {
				addDisposicion(evento, idsDisposicion);

				fecGenAmort = inicializarFechaAmortizacion(evento,
						fecGenAmort, fEjec);
			}
		}
		
		return fecGenAmort;
	}

	/**
	 * Añade el evento a la lista de disposiciones en caso de deber.
	 * @param evento Evento
	 * @param idsDisposicion List<Long>
	 */
	private void addDisposicion(Evento evento, List<Long> idsDisposicion) {
		if (evento instanceof DisposicionOperacion
				&& evento.getEsEstadoActivo()
				&& !idsDisposicion.contains(evento.getId())
				&& !(evento instanceof DisposicionTesoreriaImp)) //ICO-51599: que no genere el plan de intereres de la disposición de tesorería
		{
			idsDisposicion.add(evento.getId());
		}
	}

	/**
	 * Inicializa la fecha de amortización.
	 * @param evento Evento
	 * @param fecGenAmort Date
	 * @param fEjec Date
	 * @return Date fechaGeneracionAmortizacion
	 */
	private Date inicializarFechaAmortizacion(Evento evento,
			Date fecGenAmort, Date fEjec) {
		if (evento instanceof AmortizacionAutomatica
				&& !evento.getFechaEvento().before(fEjec)) {
			return FechaUtils.sumaUnDiaCalendario(
					evento.getFechaEvento());
		}
		
		return fecGenAmort;
	}

	/**
	 * Añade los eventos generados a la lista de no cobrados.
	 * @param eventos List<Evento>
	 * @param eventosOperacion EventosOperacion
	 */
	private void addNoCobrados(List<Evento> eventos,
			EventosOperacion eventosOperacion) {
		for (Evento eventoCobrable : eventos) {

			if (isCobrable(eventoCobrable)||isCobrableNegativo(eventoCobrable)) {
				eventosOperacion.addEventosNoCobrados(
						eventoCobrable);
			}
		}
	}

	/**
	 * Comprueba si el evento es cobrable o no.
	 * @param e Evento
	 * @return boolean
	 */
	private boolean isCobrable(Evento e) {
		return e.getImporte() != null
				&& e.getImporte()
						.getCantidad() != null
				&& e.getImporte().compareTo(
						e.getImporteCobrado()) > 0;
	}

	/**
	 * Comprueba si el evento es cobrable o no.
	 * @param e Evento
	 * @return boolean
	 */
	private boolean isCobrableNegativo(Evento e) {
		return e.getImporte() != null
				&& e.getImporte()
						.getCantidad() != null
				&& e.getImporte().compareTo(
						e.getImporteCobrado()) < 0;
	}
	/**
	 * Devuelve true si existe una amortización anticipada
	 * entre los eventos.
	 * @param eventos List<Evento>
	 * @return boolean
	 */
	private boolean existeAnticipada(List<Evento> eventos) {
		for (Evento ev : eventos) {
			if (isAnticipada(ev)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Devuelve true si el evento es una amortización anticipada.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isAnticipada(Evento ev) {
		return isAnticipadaVoluntaria(ev)
				|| isReintegro(ev)
				|| isDevolDisp(ev)
				|| isDevolucionFactura(ev)
				|| isAnticipadaAutorizada(ev)
				|| isAnticipadaObligatoria(ev)
				|| isCondonacionFinalizacion(ev);
	}

	/**
	 * Devuelve true si el evento es una voluntaria.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isAnticipadaVoluntaria(Evento ev) {
		return ev instanceof AmortizacionAnticipadaVoluntariaImp;
	}

	/**
	 * Devuelve true si el evento es una Condonacion Finalizacion.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isCondonacionFinalizacion(Evento ev) {
		return ev instanceof AmortizacionCondonacionFinalizacionImp;
	}
	
	/**
	 * Devuelve true si el evento es un reintegro.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isReintegro(Evento ev) {
		return ev instanceof AmortizacionAnticipadaReintegroImp;
	}
	
	/**
	 * Devuelve true si el evento es un devolucion disposicion.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isDevolDisp(Evento ev) {
		return ev instanceof AmortizacionDevolucionDisposicionImp; //ICO-81899
	}

	/**
	 * Devuelve true si el evento es una devolución de factura.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isDevolucionFactura(Evento ev) {
		return ev instanceof AmortizacionAnticipadaDevolucionFacturaImp;
	}

	/**
	 * Devuelve true si el evento es una amortización autorizada.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isAnticipadaAutorizada(Evento ev) {
		return ev instanceof AmortizacionAnticipadaAutorizadaImp;
	}

	/**
	 * Devuelve true si el evento es una amortización obligatoria.
	 * @param ev Evento
	 * @return boolean
	 */
	private boolean isAnticipadaObligatoria(Evento ev) {
		return ev instanceof AmortizacionAnticipadaObligatoriaImp;
	}

	/**
	 * Borra los planes innecesarios de ejecución.
	 * @param planesEjecucion List<PlanEvento>
	 */
	private void borrarPlanesInnecesarios(
			List<PlanEvento> planesEjecucion) {
		for (int i = 0; i < planesEjecucion.size(); i++) {
			PlanEvento p = planesEjecucion.get(i);
			if (p instanceof PlanCalendarioComisionAsociadoImp
					&& p.getAmortizacion() != null) {
				planesEjecucion.remove(i);
			}
		}
	}

	/**
	 * Método que ejecuta los saldos de un listado de eventos cuando sean
	 * posteriores a la fecha de ejecucion.
	 *
	 * @param eventos
	 *                listado de eventos a ejecutar los saldos
	 * @param fEjec
	 *                fecha de ejecucion de los planes
	 * @param idOperacion
	 *                id de la operacion
	 * @param idsDis
	 *                lista de ids de disposicion de la operacion
	 * @param evOpe
	 *                contenedor de eventos
	 * @param saldos
	 *                contenedor de saldos
	 * @param cobros
	 *                coleccion de cobros de la operación
	 * @param coEv cobros evento
	 * @throws POJOValidationException POJOValidationException
	 */
	private void ejecutarEventos(List<Evento> eventos, Date fEjec,
			Long idOperacion, List<Long> idsDis,
			EventosOperacion evOpe,
			SaldosTotalesOp saldos, Map<Date, List<Cobro>> cobros,
			List<CobroEvento> coEv,
			List<PlanEvento> planesEjecucion)
			throws POJOValidationException {

		if (eventos != null) {
			Collections.sort(eventos, new TipoEventoComparator());

			// Se hace el reajuste de saldos en caso de que tengamos
			// un saldo en
			// exceso
			// antes del primer evento de la lista de eventos a
			// mantener
			if (!eventos.isEmpty()) {
				Date fecha = null;

				for (Evento evento : eventos) {
					if (!FechaUtils.truncateDate(
							evento.getFechaEvento())
							.before(fEjec)) {
						fecha = evento.getFechaEvento();
						break;
					}
				}

				if (fecha != null) {
					Set<Map.Entry<Date, List<Cobro>>> listaFechasCobros = cobros
							.entrySet();
					Iterator<Map.Entry<Date, List<Cobro>>> it = listaFechasCobros
							.iterator();

					while (it.hasNext()) {
						Map.Entry<Date, List<Cobro>> entry = it
								.next();
						Cobro c = null;
						Date fCobro = entry.getKey();
						if(entry.getValue().size() > 0) {
							c = (Cobro) entry.getValue().get(0);
							if(c.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_MINISTERIO.getCodigo()) ||
								c.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_AUTONOMICO.getCodigo())) {
									fCobro = c.getFechaVencimiento();
							}
						}
						if (fCobro.before(fecha)) {
							reajustarExceso(saldos,
									fCobro,
									evOpe,
									cobros,
									fEjec,
									idsDis,
									coEv);
						}
					}
				}
			}

			// ejecutamos los saldo década
			for (Evento e : eventos) {
				// no se ejecuta el saldo si es un evento
				// parcial
				if (e instanceof EventoAutomatico) {
					if (isParcial(e)) {
						continue;
					}
				}
				// no se ejecuta el saldo si es un evento en
				// estado de baja
				if (e.getEsEstadoActivo()) {
					// se ejecuta el saldo cuando la fecha
					// del evento no sea
					// anterior a la fecha de ejecucion
					if (!FechaUtils.truncateDate(
							e.getFechaEvento())
							.before(fEjec)) {
						evOpe.doSaldo(saldos,
								e, idOperacion,
								idsDis, cobros,
								fEjec, fEjec,
								coEv, null, null);
						deleteEvento(evOpe,
								e);
						
						/** ICO-53953 - Si se ejecuta una liquidación de interés de disposición, no es necesario ejecutar su recálculo
						 * por lo que borro el plan. **/
						if(e instanceof LiquidacionInteresesAutomaticaDisposicion) {
							LiquidacionInteresesAutomaticaDisposicion l = ((LiquidacionInteresesAutomaticaDisposicion)e);
							if(l.getPlanInteresDisposicion() != null && l.getPlanInteresDisposicion().getDisposicion() != null) {
								borrarPlanesInteresDisposicion(planesEjecucion, l.getPlanInteresDisposicion().getDisposicion().getId());
							}							
						}
						/** FIN ICO-53953 */
					}
					// se ejecuta el saldo de los eventos
					// dependientes del
					// evento
					doSaldoDependientes(coEv, e, fEjec,
							evOpe, saldos,
							idOperacion, idsDis,
							cobros);
				}
			}
		}
	}

	/**
	 * Reajusta el saldo en exceso.
	 * @param saldos SaldosTotalesOp
	 * @param fechaCobro Date
	 * @param evOpe EventosOperacion
	 * @param cobros Map<Date, List<Cobro>>
	 * @param fEjec Date
	 * @param idsDis  List<CobroEvento>
	 * @param coEv List<CobroEvento>
	 * @throws POJOValidationException Excepcion
	 */
	private void reajustarExceso(SaldosTotalesOp saldos, Date fechaCobro,
			EventosOperacion evOpe, Map<Date, List<Cobro>> cobros,
			Date fEjec, List<Long> idsDis, List<CobroEvento> coEv)
			throws POJOValidationException {
		SaldosOp s = saldos.getSaldosOp(fechaCobro);
		evOpe.reajustarSaldoExceso(s, null, cobros, fEjec, saldos,
				idsDis, coEv, null, null);
	}

	/**
	 * Devuelve true si el evento es automático y parcial.
	 * @param e Evento
	 * @return boolean
	 */
	private boolean isParcial(Evento e) {
		return ((EventoAutomatico) e).getEventoTotal() != null;
	}

	/**
	 * Ejecuta el doSaldo de los eventos dependientes del evento.
	 * @param cobrosEventos List<CobroEvento>
	 * @param evento Evento
	 * @param fechaEjecucion Date
	 * @param eventosOperacion EventosOperacion
	 * @param saldos SaldosTotalesOp
	 * @param idOpe Long
	 * @param idsDisposicion List<Long>
	 * @param cobros Map<Date, List<Cobro>>
	 * @throws POJOValidationException Excepcion
	 */
	private void doSaldoDependientes(List<CobroEvento> cobrosEventos,
			Evento evento, Date fechaEjecucion,
			EventosOperacion eventosOperacion,
			SaldosTotalesOp saldos, Long idOpe,
			List<Long> idsDisposicion,
			Map<Date, List<Cobro>> cobros)
			throws POJOValidationException {
		boolean cobrado = false;
		for (Object eventoDep : evento.getEventosDependientes()) {
			cobrado = false;
			Evento e = (Evento) eventoDep;

			for (CobroEvento ce : cobrosEventos) {
				if (ce.getEventoAsociado().getId()
						.equals(e.getId())) {
					if (ce.getImporteCobrado().equals(
							e.getImporte())) {
						cobrado = true;
					}
				}

			}

			if (!FechaUtils.truncateDate(e.getFechaEvento())
					.before(fechaEjecucion)
					&& e.getEsEstadoActivo()) {
				if (!cobrado) {
					eventosOperacion.addEventosNoCobrados(
							e);
				}
				eventosOperacion.doSaldo(saldos, e, idOpe,
						idsDisposicion, cobros,
						fechaEjecucion, fechaEjecucion,
						cobrosEventos, null, null);
				deleteEvento(eventosOperacion, e);
			}
		}
	}

	/**
	 * Borra el evento del set de eventosOperación.
	 * @param eventosOperacion EventosOperacion
	 * @param evento Evento
	 */
	private void deleteEvento(EventosOperacion eventosOperacion,
			Evento evento) {
		eventosOperacion.getEventosParaDoSaldo().remove(evento);
	}

	/**
	 * Método que ejecuta los saldos de un listado de cobroseventos cuando
	 * sean cobroseventos con la misma fecha de recalculo.
	 * @param cobros
	 *                coleccion de cobros de la operación
	 * @param idDisps
	 *                lista de ids de disposicion de la operacion
	 * @param saldos
	 *                contenedor de saldos
	 * @throws POJOValidationException Excepción
	 */
	private void ejecutarCobrosEventos(SaldosTotalesOp saldos,
			Map<Date, List<Cobro>> cobros,
			List<Long> idDisps, Date fEjec) { // ICO-118218

		Collection<List<Cobro>> temp = cobros.values();

		Iterator<List<Cobro>> it = temp.iterator();

		while (it.hasNext()) {
			List<Cobro> listaCobros = it.next();

			for (Cobro cobro : listaCobros) {
				if (cobro.getCobrosEventos() != null
						&& !cobro.getCobrosEventos().isEmpty()) {
					recorreCobroEventosParaDoSaldoEvAsociado(cobro, saldos, idDisps, fEjec); // ICO-118218
				}
			}
		}
	}
	
	/** INI ICO-118218
	 * Recorre la lista de Eventos asociados a un cobro para actualizar el saldo
	 * @param cobro
	 * @param saldos
	 * @param idDisps
	 */
	protected void recorreCobroEventosParaDoSaldoEvAsociado(Cobro cobro, SaldosTotalesOp saldos, List<Long> idDisps, Date fEjec) {
		for (CobroEvento cobroEvento : cobro
				.getCobrosEventos()) {
			if(cobroEvento.getEventoAsociado().getFechaVencimientoAjustada() == null
					|| cobro.getFechaCobro().compareTo(cobroEvento.getEventoAsociado().getFechaVencimientoAjustada()) > 0 
					|| cobroEvento.getEventoAsociado().getFechaEvento().compareTo(fEjec) >= 0) {
				doSaldoEvAsociado(saldos,
						cobroEvento,
						cobro, idDisps);
			}
		}
	}
	// FIN ICO-118218

	/**
	 * Ejectuta el saldo del evento asociado.
	 * @param saldos SaldosTotalesOp
	 * @param cobroEvento CobroEvento
	 * @param cobro Cobro
	 * @param idDisposiciones List<Long>
	 */
	private void doSaldoEvAsociado(SaldosTotalesOp saldos,
			CobroEvento cobroEvento, Cobro cobro,
			List<Long> idDisposiciones) {
		
		Date fechaAsociarSaldo = cobro.getFechaCobro();

		if(fechaAsociarSaldo.before(cobroEvento.getEventoAsociado().getFechaEvento())) {
			fechaAsociarSaldo = cobroEvento.getEventoAsociado().getFechaEvento();
		}
		
		cobroEvento.getEventoAsociado().doSaldo(
				saldos.getSaldosOp(cobro.getFechaCobro()),
				saldos.createSaldosDisp(cobro.getFechaCobro(),
						cobro.getOperacion().getId(),
						idDisposiciones),
				cobroEvento.getImporteCobrado().getCantidad(),
				fechaAsociarSaldo);
	}

	/**
	 * Metodo que ejecuta planes de eventos en funcion de los parametros
	 * recibidos.
	 * @param planesEjecucion
	 *                listado de planes a ejecutar
	 * @param fechaEjecucion
	 *                fecha de ejecucion de los planes
	 * @param idOperacion
	 *                id de la operacion
	 * @param idsDisposicion
	 *                lista de ids de disposicion de la operacion
	 * @param eventosOperacion
	 *                contenedor de eventos
	 * @param saldos
	 *                contenedor de saldos
	 * @param cobros
	 *                coleccion de cobros de la operación
	 * @param fechaGeneracionAmortizaciones
	 *                fecha desde la que se generaran amortizaciones
	 * @param festivos
	 *                listado de dias festivos
	 * @param fechaFin
	 *                fecha de simulacion
	 * @param capitalizacion
	 *                indica si la generacion es por capitalizacion
	 * @param hayQueRegenerarSubsidiosDiaMas
	 *                indica si hay que generar subsidio a un dia mas
	 * @param fechaDesdeCobroSubsidio fecha del cobro
	 * @param cobrosEventos Cobros de eventos
	 * @param operacion Operacion
	 *
	 * @throws POJOValidationException Excepción.
	 * @throws SQLException Excepción.
	 */
	private void ejecutarPlanesEvento(
			List<PlanEvento> planesEjecucion,
			Date fechaEjecucion, Long idOperacion,
			List<Long> idsDisposicion,
			EventosOperacion eventosOperacion,
			SaldosTotalesOp saldos,
			Map<Date, List<Cobro>> cobros,
			Date fechaGeneracionAmortizaciones,
			List<Date> festivos, boolean fechaFin,
			boolean capitalizacion,
			boolean hayQueRegenerarSubsidiosDiaMas,
			Date fechaDesdeCobroSubsidio,
			List<CobroEvento> cobrosEventos,
			Operacion operacion,SaldosTotalesOp saldosAux, boolean tieneCuotaSig, SaldosTotalesOp saldosParaCom)
			throws POJOValidationException, SQLException {

		HashMap<Date, List<Cobro>> cobrosAux = null;
		cobrosAux = new HashMap<Date, List<Cobro>>();
		List<PlanEvento> planesSubsidios = new ArrayList<PlanEvento>();
		
		boolean existePlanDemoraOSubsidio = false;	// ICO-104147
		
		eventosOperacion.setCodigoProducto(helper
				.buscarProducto(idOperacion));
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				idOperacion.toString() )) {
			LOG_ICO_62556.info("Se inicia ejecutarPlanesEvento");
		}

		/**
		 * Si la operación es de EELL no nos interesa que se
		 * sobreescriba la base de cálculo de las disposiciones, ya que
		 * para los ACT/ACT se ha dejado ACT/365 en algunas
		 * disposiciones para que funcione bien el bisiesto.
		 */
		if (!(operacion instanceof OperacionEL)) {
			helper
					.actualizarBasePDIS(idOperacion);
		}

		for (PlanEvento planEvento : planesEjecucion) {
			if (TipoPlanEventoEnum.PLAN_DEMORAS.equals(
						planEvento.getTipoPlanEvento())) {
					
				if(operacion instanceof OperacionFD && !esCarteraTraspasada(operacion)) { //ICO-62994
				cobrosAux = helper
						.recuperaCobrosParaDemoras(
									operacion.getFechaFormalizacion(), //ICO-62994
									operacion);
				} else {
					cobrosAux = helper
							.recuperaCobrosParaDemoras(
								fechaEjecucion,
								operacion);
				}
			}
		}
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				idOperacion.toString() )) {
			LOG_ICO_62556.info("Ha recuperado los cobros puntuales de demoras");
		}
				
		if (operacion instanceof OperacionVPO) { // ICO-104147
			existePlanDemoraOSubsidio = ordenaPlanesEventosVPO(planesEjecucion, planesSubsidios);
			
			if(!existePlanDemoraOSubsidio) { // Si no hay plan de Demora ni de Subsidio
				eventosOperacion.setAsignarCobrosCCConPlanInteres(true);
			}
		}
		
		// se ejecuta cada uno de losplanes recibidos
		for (PlanEvento plan : planesEjecucion) {
			// Realizar el proceso de los planes si no es de
			// subsidios, este se
			// ejecutará cuando finalizan el resto de planes,
			// necesita los
			// eventos generados
			if (!(plan instanceof PlanSubsidioEventoAbstract) || operacion instanceof OperacionVPO) {
				
				if(LogSingletonICO62556.actualizarTrazaICO62556(
						null,
						null,
						idOperacion.toString() )) {
					LOG_ICO_62556.info("Bucle foreach, por cada plan de ejecución llama a ejecutar plan: " + plan.getTipoPlanEnOperacion());
				}
								
				ejecutaPlan(plan, fechaFin, fechaEjecucion,
						eventosOperacion, saldos,
						cobros, idOperacion,
						idsDisposicion,
						fechaDesdeCobroSubsidio,
						cobrosEventos,
						hayQueRegenerarSubsidiosDiaMas,
						fechaGeneracionAmortizaciones,
						festivos, capitalizacion,
						cobrosAux, saldosAux,tieneCuotaSig, saldosParaCom);
			} else {
				// Guarda los planes de subsidio para
				// ejecutarlos posteriormente
				planesSubsidios.add(plan);
			}
		}

		if (!planesSubsidios.isEmpty() && !(operacion instanceof OperacionVPO)) {

			for (PlanEvento planEvento : planesSubsidios) {
				
				if(LogSingletonICO62556.actualizarTrazaICO62556(
						null,
						null,
						idOperacion.toString() )) {
					LOG_ICO_62556.info("Bucle foreach para planesSubsidios, por cada plan de ejecución llama a ejecutar plan: " + planEvento.getTipoPlanEnOperacion());
				}
				
				ejecutaPlan(planEvento, fechaFin,
						fechaEjecucion,
						eventosOperacion, saldos,
						cobros, idOperacion,
						idsDisposicion,
						fechaDesdeCobroSubsidio,
						cobrosEventos,
						hayQueRegenerarSubsidiosDiaMas,
						fechaGeneracionAmortizaciones,
						festivos, capitalizacion,
						cobrosAux,saldosAux, false, saldosParaCom);
				break;
			}

		}

	}
	
	// INI ICO-104147
	private boolean ordenaPlanesEventosVPO(List<PlanEvento> planesEjecucion, List<PlanEvento> planesSubsidios) {
		
		PlanEvento planDemora = new PlanDemoraImp();
		boolean existePlanDemoraOSubsidio = false;
		boolean existePlanDemora = false;
		
		for(PlanEvento plan : planesEjecucion) {
			if(TipoPlanEventoEnum.PLAN_DEMORAS.equals(
					plan.getTipoPlanEvento())) {
				existePlanDemoraOSubsidio = true;
				existePlanDemora = true;
				
				planDemora = plan;
			}
			
			if ((plan instanceof PlanSubsidioEventoAbstract)) {
				planesSubsidios.add(plan);
			}
		}
		
		if(!planesSubsidios.isEmpty()) {
			existePlanDemoraOSubsidio = true;
			
			planesEjecucion.removeAll(planesSubsidios);
			planesEjecucion.add(planesSubsidios.get(0));
		}
		
		if(existePlanDemora) {
			planesEjecucion.remove(planDemora);
			planesEjecucion.add(planDemora);
		}
		
		return existePlanDemoraOSubsidio;
	}
	// FIN ICO-104147

	// INI ICO-62994
	private Boolean esCarteraTraspasada(Operacion op) {
		Boolean esCarteraTraspasada = false;
		
		if (op instanceof OperacionFD && op.getCodigoHost() != null &&
				(op.getCodigoHost().startsWith("1518") ||
				 op.getCodigoHost().startsWith("1519") ||
				 op.getCodigoHost().startsWith("1520"))) {
			esCarteraTraspasada = true;
		}
		
		return esCarteraTraspasada;
	}

	/**
	 * Método que ejecuta el plan e introduce en eventosOperacion los
	 * eventos generados.
	 * @param planEvento
	 *                plan a ejecutar.
	 * @param fechaFin
	 *                si viene de simulación, se pone fecha fin.
	 * @param fechaEjecucion
	 *                fecha de ejecución del cuadro.
	 * @param eventosOperacion
	 *                En esta clase de almacenan los eventos.
	 * @param saldos
	 *                Saldos de la operación.
	 * @param cobros
	 *                Cobros de la operación.
	 * @param idOperacion
	 *                Id de la operación.
	 * @param idsDisposicion
	 *                Ids de todas las disposiciones del préstamo.
	 * @param fechaDesdeCobroSubsidio
	 *                Fecha desde donde crear cobros de subs.
	 * @param cobrosEventos
	 *                Lista de relaciones entre cobros y eventos.
	 * @param hayQueRegenerarSubsidiosDiaMas
	 *                Necesidad de regenerar un día más.
	 * @param fechaGeneracionAmortizaciones
	 *                Fecha generación amortizaciones.
	 * @param festivos
	 *                Lista con los festivos.
	 * @param capitalizacion
	 *                Si la operación es capitalizada.
	 * @param cobrosAux
	 *                Lista de cobros auxiliar.
	 * @throws POJOValidationException
	 *                 Excepción en caso de error.
	 */
	private void ejecutaPlan(PlanEvento planEvento,
			boolean fechaFin, Date fechaEjecucion,
			EventosOperacion eventosOperacion,
			SaldosTotalesOp saldos,
			Map<Date, List<Cobro>> cobros,
			Long idOperacion, List<Long> idsDisposicion,
			Date fechaDesdeCobroSubsidio,
			List<CobroEvento> cobrosEventos,
			boolean hayQueRegenerarSubsidiosDiaMas,
			Date fechaGeneracionAmortizaciones,
			List<Date> festivos, boolean capitalizacion,
			HashMap<Date, List<Cobro>> cobrosAux,SaldosTotalesOp saldosAux, boolean tieneCuotaSig, SaldosTotalesOp saldosParaCom)
			throws POJOValidationException {
		
		// se asigna la fecha hasta la que se ejecuta el plan en caso de
		// venir
		// indicada desde la simulacion
		if (fechaFin) {
			planEvento.setFechaFinalSimulacion(fechaEjecucion);
			planEvento.setEsSimulacion(true);
		} else {
			planEvento.setFechaFinalSimulacion(null);
		}

		// se ejecutan los saldo en caso de que el plan necesite que
		// estén
		// actualizados
		if (planEvento.necesitaSaldosActualizados()) {
			
			if (planEvento.getOperacion() instanceof OperacionVPO && TipoPlanEventoEnum.PLAN_DEMORAS.equals(planEvento.getTipoPlanEvento())) { // ICO-104147
				asignarCobrosCC(planEvento, eventosOperacion, cobros, saldos, fechaEjecucion, idsDisposicion, cobrosEventos);
			}
			
			eventosOperacion.doSaldos(saldos, cobros, idOperacion,
					idsDisposicion, fechaEjecucion,
					fechaDesdeCobroSubsidio, cobrosEventos, festivos, planEvento.getOperacion());
		}

		List<EventoAutomatico> eventosGenerados = null;

		try {
			// Este bloque de código informa la propiedad boolean de
			// los objetos
			// PlanInteresOperacion para que la lógica interna del
			// método
			// doEventos pueda decidir si debe ajustar la fecha del
			// plan o no
			if (planEvento.getClass()
					.equals(PlanInteresOperacion.class)) {
				setProductoAvales(planEvento, eventosOperacion,
						idOperacion);
			}
		} catch (SQLException e) {
			throw new POJOValidationException(e);
		} catch (Exception e) {
			throw new POJOValidationException(e);
		}

		// se indica la fecha desde la que debe generar amortizaciones
		// en caso
		// de tratarse de un plan de amortizacion o subsidio
		if (isEjecutable(planEvento, hayQueRegenerarSubsidiosDiaMas)) {

			// hasta existir disposiciones.
			if (isPlanAmortizacionEjecutable(planEvento,
					idsDisposicion)) {
				
			if((planEvento.getOperacion() instanceof OperacionLM || planEvento instanceof PlanAmortizacionOperacionConstanteImp)
					||(planEvento instanceof PlanAmortizacionOperacionPorcentualDispImp)
					||(planEvento instanceof PlanAmortizacionOperacionPorcentualFormImp)
					||(planEvento instanceof PlanAmortizacionOperacionFrancesConstanteImp)) {
				
					eventosGenerados = planEvento.doEventos(fechaGeneracionAmortizaciones,
							saldosAux, eventosOperacion,festivos, null);

			}
			else {
				
				eventosGenerados = planEvento.doEventos(
						fechaGeneracionAmortizaciones,
						saldos, eventosOperacion,
						festivos, null);
				
			
			}
		  }
		} else {
			if (isPlanEjecutable(capitalizacion, planEvento)) {
				// ejecuta el plan
				if (isPlanComisionDisp(planEvento)) { //ICO-62408 plan comision disponibilidad con saldosAux (contiene saldos desde inicio)
					eventosGenerados = planEvento.doEventos(
							FechaUtils.sumaUnDiaCalendario(
									fechaEjecucion),
							saldosParaCom, eventosOperacion,
							festivos, null);
				}else {
					eventosGenerados = planEvento.doEventos(
							FechaUtils.sumaUnDiaCalendario(
									fechaEjecucion),
							saldos, eventosOperacion,
							festivos, null);
				}
			} else if (TipoPlanEventoEnum.PLAN_DEMORAS.equals(
					planEvento.getTipoPlanEvento())) {

				setFechaPrimerVencDem((PlanDemora) planEvento);
				
				if(planEvento.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(planEvento.getOperacion())) { //ICO-62994
					eventosGenerados = planEvento.doEventos(
							fechaEjecucion, saldosParaCom,
							eventosOperacion, festivos,
							cobrosAux);
				} else {
					eventosGenerados = planEvento.doEventos(
							fechaEjecucion, saldos,
							eventosOperacion, festivos,
							cobrosAux);
				}
			} else if(isPlanComisionDisp(planEvento))  { //ICO-62408 plan comision disponibilidad con saldosAux (contiene saldos desde inicio)
				// ejecuta el plan comision disponibilidad
				eventosGenerados = planEvento.doEventos(
						fechaEjecucion, saldosParaCom,
						eventosOperacion, festivos,
						null);
			} else {

				if (TipoPlanEventoEnum.PLAN_SUBSIDIOS_VPO.equals(planEvento.getTipoPlanEvento())) {
					eventosGenerados = planEvento.doEventos(fechaEjecucion, saldosParaCom, eventosOperacion, festivos,
							null);
					eventosOperacion.setAsignarCobrosCC(true);
				} else {
					// ejecuta el plan crea subsidios meter validacion para plan subsidio y con los
					// saldos como saldosParaCom
					eventosGenerados = planEvento.doEventos(fechaEjecucion, saldos, eventosOperacion, festivos, null);
				}
			}
		}
		// añade los eventos que ha generado el plan al contenedor
		eventosOperacion.addEventosPlan(planEvento, eventosGenerados);
		// añade los eventos a eventos no cobrados
		if (eventosGenerados != null) {
			for (Evento eventoGenerado : eventosGenerados) {
				eventosOperacion.addEventosNoCobrados(
						eventoGenerado);
			}
		}
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				idOperacion.toString() )) {
			LOG_ICO_62556.info("Termina de ejecutar plan");
		}
		
		if(eventosOperacion.isAsignarCobrosCCConPlanInteres() && TipoPlanEventoEnum.PLAN_INTERESES.equals(planEvento.getTipoPlanEvento())) {
			eventosOperacion.setAsignarCobrosCC(true);
		}
		if (planEvento.getOperacion() instanceof OperacionVPO && TipoPlanEventoEnum.PLAN_INTERESES.equals(planEvento.getTipoPlanEvento())) {
			informaImporteCuotaCliente(eventosOperacion.getAmortizaciones(), eventosOperacion.getLiquidacionIntereses(), eventosOperacion.getLiquidacionIntereses());
		}
		eventosOperacion.doSaldos(saldos, cobros, idOperacion,
				idsDisposicion, fechaEjecucion,
				fechaDesdeCobroSubsidio, cobrosEventos, festivos, planEvento.getOperacion());
		if(planEvento.getOperacion() instanceof OperacionEM) {
			for(SaldosOp saldosNext : saldos.getSaldosOperacion()) {
				saldosNext.setSaldoCapitalP(saldosNext.getSaldoCapitalPV());
				saldosNext.setSaldoTotalP(saldosNext.getSaldoCapitalPV());
			}
		}
		
		// INI ICO-62994 Una vez actualizada la variable saldos, la utilizamos para actualizar la variables saldosParaCom
		saldosParaCom.getSaldosOperacion().removeAll(saldos.getSaldosOperacion());
		
		saldosParaCom.getSaldosOperacion().addAll(saldos.getSaldosOperacion());
		// FIN ICO-62994
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				idOperacion.toString() )) {
			LOG_ICO_62556.info("Termina de ejecutar doSaldos de los eventos generados");
		}
	}
	
	// INI ICO-104147
		public void informaImporteCuotaCliente(List<Evento> listaAmortizaciones, List<Evento> listaInteresesAnteriores, List<Evento> listaIntereses) {

		    for (int i = 0; i < listaIntereses.size(); i++) {
		        Evento liquidacion = listaIntereses.get(i);
		        BigDecimal liquidacionCantidad = BigDecimal.ZERO;
		        Date liquidacionFechaEvento = liquidacion.getFechaEvento();

		        if (liquidacion.getClass().getSimpleName().contains("Disposicion") && i != 0) {
		            liquidacion.getImporteCuotaCliente().setCantidad(BigDecimal.ZERO);
		            continue;
		        }

		        liquidacionCantidad = listaInteresesAnteriores.stream()
		            .filter(interesListado -> liquidacionFechaEvento.compareTo(interesListado.getFechaEvento()) == 0)
		            .map(interesFiltrada -> interesFiltrada.getImporte().getCantidad())
		            .reduce(liquidacionCantidad, BigDecimal::add);


		        BigDecimal cuotaClienteCantidad = listaAmortizaciones.stream()
		            .filter(amortizacionListada -> liquidacionFechaEvento.compareTo(amortizacionListada.getFechaEvento()) == 0)
		            .map(amortizacionFiltrada -> amortizacionFiltrada.getImporte().getCantidad())
		            .reduce(liquidacionCantidad, BigDecimal::add);

		            liquidacion.getImporteCuotaCliente().setCantidad(cuotaClienteCantidad);
		    }
		}
	
	// INI ICO-104147
	private void asignarCobrosCC(PlanEvento planEvento, EventosOperacion eventosOperacion, Map<Date, List<Cobro>> cobros, SaldosTotalesOp saldos, 
			Date fechaEjecucion, List<Long> idsDisposicion, List<CobroEvento> cobrosEventos) throws POJOValidationException {
		
		Map<Date, List<Cobro>> mapCobrosCC = new TreeMap<>();
		List<Cobro> listaCobros = new ArrayList<>();
		
		eventosOperacion.setAsignarCobrosCC(true);
		
		if(cobros.size() > 0) {
			for (Map.Entry<Date, List<Cobro>> cobrosfechaiterador : cobros.entrySet()) {
				for (Cobro cobro : cobrosfechaiterador.getValue()) {
					if(cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_CUOTA_CLIENTE.getCodigo())) {
						listaCobros.add(cobro);
					}
				}
				
				if(!listaCobros.isEmpty()) {
					mapCobrosCC.put(cobrosfechaiterador.getKey(), new ArrayList<>(listaCobros));
					listaCobros.clear();
				}
			}
			
			if(mapCobrosCC.size() > 0) {
				for(Map.Entry<Date, List<Cobro>> cobrosfechaiterador : mapCobrosCC.entrySet()) {
					reajustarExceso(saldos,cobrosfechaiterador.getKey(),eventosOperacion,mapCobrosCC,fechaEjecucion,idsDisposicion,cobrosEventos);
				}
			}
		}
		
		eventosOperacion.setAsignarCobrosCC(false);
	}
	// FIN ICO-104147
	
	/**
	 * Setea el producto para avales.
	 * @param p Planevento
	 * @param eventosOperacion EventosOperacion
	 * @param idOperacion Long
	 * @throws SQLException Excepción
	 * @throws Exception Excepción
	 */
	private void setProductoAvales(PlanEvento p,
			EventosOperacion eventosOperacion,
			Long idOperacion)
			throws SQLException, Exception {
		eventosOperacion.setCodigoProducto(
				helper
						.buscarProducto(idOperacion));

		RegistroAvalesService regAvales = getRegistroAvalesService();
		((PlanInteresOperacion) p).setProductoAvales(
				regAvales.isProductoAvales(eventosOperacion
						.getCodigoProducto()));
	}

	/**
	 * Recupera el service de RegistroAvalesService.
	 * @return RegistroAvalesService
	 */
	private RegistroAvalesService getRegistroAvalesService() {
		return (RegistroAvalesService) ServiceLocator.getInstance()
				.getObjetoDeContexto("RegistroAvalesService#"
						+ RegistroAvalesService.class
								.getName());
	}

	/**
	 * Devuelve true si el plan es Ejecutable.
	 * @param p PlanEvento
	 * @param hayQueRegenerarSubsidiosDiaMas si hay que regenerar subs.
	 * @return boolean
	 */
	private boolean isEjecutable(PlanEvento p,
			boolean hayQueRegenerarSubsidiosDiaMas) {
		return (p.getTipoPlanEvento()
				.equals(TipoPlanEventoEnum.PLAN_AMORTIZACION))
				|| (hayQueRegenerarSubsidiosDiaMas
						&& isPlanSubsidio(p));
	}

	/**
	 * Devuelve true si el plan es de tipo subsidio.
	 * @param p PlanEvento
	 * @return boolean
	 */
	private boolean isPlanSubsidio(PlanEvento p) {
		return p.getTipoPlanEvento()
				.equals(TipoPlanEventoEnum.PLAN_SUBSIDIOS);
	}

	/**
	 * Devuelve true si el plan de amortización es ejecutable.
	 * @param planEvento Plan de amortización
	 * @param idsDisposicion idsDisposición
	 * @return boolean
	 */
	private boolean isPlanAmortizacionEjecutable(
			PlanEvento planEvento,
			List<Long> idsDisposicion) {
		return !(isPlanPorcentualForm(planEvento)
				&& (idsDisposicion == null
						|| idsDisposicion.isEmpty()));
	}

	/**
	 * Devuelve true si el plan es ejecutable.
	 * @param capitalizacion si viene de capitalización
	 * @param planEvento Plan de Evento a ejecutar
	 * @return boolean
	 */
	private boolean isPlanEjecutable(boolean capitalizacion,
			PlanEvento planEvento) {
		TipoPlanEventoEnum t = planEvento.getTipoPlanEvento();
		return capitalizacion && (isPlanComision(t)
				|| isPlanInteresDisposicion(t)
				|| isPlanInteresOperacion(t));
	}
	
	/**
	 * Devuelve true si el plan es Comision Disponibilidad.
	 * @param planEvento Plan de Evento a ejecutar
	 * @return boolean
	 */
	private boolean isPlanComisionDisp(PlanEvento planEvento) {
		return planEvento instanceof PlanCalendarioComisionPeriodico && 
				((PlanCalendarioComisionPeriodico)planEvento).getPlanComision() instanceof PlanComisionDisponibilidadImp;
	}

	/**
	 * Devuelve true si el plan de amortización es Porcentual Formalizado.
	 * @param p Plan Evento
	 * @return boolean
	 */
	private boolean isPlanPorcentualForm(PlanEvento p) {
		return p instanceof PlanAmortizacionOperacionPorcentualFormImp;
	}

	/**
	 * Devuelve true si el plan es de comisión.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanComision(TipoPlanEventoEnum tipo) {
		return tipo.equals(TipoPlanEventoEnum.PLAN_COMISION);
	}


	/**
	 * Devuelve true si el plan es de amortización.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanAmort(TipoPlanEventoEnum tipo) {
		return tipo.equals(TipoPlanEventoEnum.PLAN_AMORTIZACION);
	}

	/**
	 * Devuelve true si el plan es de demora.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanDemora(TipoPlanEventoEnum tipo) {
		return tipo.equals(TipoPlanEventoEnum.PLAN_DEMORAS);
	}

	/**
	 * Devuelve true si el plan es de subsidio.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanSubsidio(TipoPlanEventoEnum tipo) {
		return tipo.equals(TipoPlanEventoEnum.PLAN_SUBSIDIOS);
	}

	/**
	 * Devuelve true si el plan es de interés de disposición.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanInteresDisposicion(
			TipoPlanEventoEnum tipo) {
		return tipo.equals(
				TipoPlanEventoEnum.PLAN_INTERESES_DISPOSICION);
	}

	/**
	 * Devuelve true si el plan es de interés de operación.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanInteresOperacion(
			TipoPlanEventoEnum tipo) {
		return tipo.equals(
				TipoPlanEventoEnum.PLAN_INTERESES);
	}

	/**
	 * Devuelve true si el plan es de interés de operación.
	 * @param tipo Tipo de plan
	 * @return boolean
	 */
	private boolean isPlanInteres(
			TipoPlanEventoEnum tipo) {
		return isPlanInteresOperacion(tipo) || isPlanInteresDisposicion(tipo);
	}
	/**
	 * Setea la fecha del primer vencimiento de demoras.
	 * @param planDemora
	 *                Plan de demora.
	 * @throws POJOValidationException
	 *                 Excepción.
	 */
	private void setFechaPrimerVencDem(PlanDemora planDemora)
			throws POJOValidationException {

		Date fecha = null;

		if (planDemora.getConceptosDemora().size() > 0) {
			try {
				fecha = getFechaPrimerEventoDemorable(
						planDemora);
			} catch (SQLException e) {
				throw new POJOValidationException(e);
			}

			planDemora.setFechaPrimerVencDemPorEvento(fecha);
		}
	}

	/**
	 * Devuelve la primera fecha demorable.
	 * @param planDemora Plan de demora
	 * @return Date fecha
	 * @throws SQLException Excepción
	 */
	private Date getFechaPrimerEventoDemorable(PlanDemora planDemora)
			throws SQLException {
		List<ConceptoDemoraEnum> conceptos = null;
		conceptos = new ArrayList<ConceptoDemoraEnum>();
		for (String concepto : planDemora.getConceptosDemora()) {
			conceptos.add(ConceptoDemoraEnum
					.getEnumByCode(concepto));
		}
		return new EventosOperacionJDBCHelper(getDataSource())
				.getFechaPrimerEventoDemorable(
						planDemora.getOperacion()
								.getId(),
						conceptos);
	}

	/**
	 * Metodo que obtiene las ids de un listado de eventos.
	 * @param eventos
	 *                listado de eventos
	 * @return List<Long> listado de ids de eventos
	 */
	private List<Long> getIdsEventos(List<Evento> eventos) {
		List<Long> idsEventos = new ArrayList<Long>();
		//Collections.sort(eventos, new EventoIdComparator());
		Collections.sort(eventos, new EventoFechaEventoComparator());//ICO-53341 - 31/10/2018
		Iterator<Evento> itEventos = eventos.iterator();
		while (itEventos.hasNext()) {
			Evento evento = itEventos.next();
			if (!idsEventos.contains(evento.getId())) {
				idsEventos.add(evento.getId());
				if (evento.getEsEstadoActivo()) {
					initIdsSinSubsidios(evento, idsEventos);
				}
			} else {
				itEventos.remove();
			}
		}

		return idsEventos;
	}

	/**
	 * Inicializa la lista de ids sin subsidios.
	 * @param evento
	 *                Evento con dependientes.
	 * @param idsEventos
	 *                Lista sin subsidios.
	 */
	private void initIdsSinSubsidios(Evento evento,
			List<Long> idsEventos) {
		List<Evento> evs = new ArrayList<Evento>(
				evento.getEventosDependientes());
		if (evs != null && evs.size() > 0) {
			borrarSubsidios(evs);

			idsEventos.addAll(getIdsEventos(evs));
		}
	}

	/**
	 * Borrar los subsidios.
	 * @param evs Lista eventos
	 */
	private void borrarSubsidios(List<Evento> evs) {
		Iterator<Evento> it = evs.iterator();

		while (it.hasNext()) {
			if (it.next().getTipoEvento().equals(
					TipoEventoEnum.LIQUIDACION_SUBSIDIOS)) {
				it.remove();
			}
		}
	}

	/**
	 * ICO-42413 Calcula el valor que se le asignará al formalizado de la
	 * operación cuando esta tenga un plan de amortización porcentual
	 * formalizado. Cuando se recalculan las amortizaciones con este plan de
	 * amortización, las que son posteriores a la fecha de recálculo toman
	 * como importe base de recálculo el formalizado de la operación. Y hay
	 * que tener en cuenta el importe de las amortizaciones anteriores a la
	 * fecha de recálculo Por ello se le resta al formalizado de la
	 * operación el importe de las amortizaciones anteriores a la fecha de
	 * recálculo
	 * @param planesEjecucion
	 *                Planes de ejecución de la operación.
	 * @param amortizaciones
	 *                Amortizaciones anteriores.
	 */
	private void calculaNuevoFormalizado(
			List<PlanEvento> planesEjecucion,
			List<Evento> amortizaciones) {

		for (Object o : planesEjecucion) {
			if (isPlanFormalizado(o)) {
				PlanEvento plan = cast(o);

				BigDecimal amortizado = BigDecimal.ZERO;
				BigDecimal formalizado = BigDecimal.ZERO;

				for (Evento ev : amortizaciones) {
					amortizado = amortizado.add(ev
							.getImporte()
							.getCantidad());
				}
				
				formalizado = plan.getOperacion().getImporteTotalFormalizado().getCantidad().subtract(amortizado);
				plan.getOperacion().getImporteFormalizado().setCantidad(formalizado);
			}
		}
	}

	/**
	 * Comprueba si el objeto es PlanPorcentualVariableFormalizado.
	 * Método creado para solucionar errores checkstyle.
	 * @param o Objecto
	 * @return true si es el plan indicado.
	 */
	private boolean isPlanFormalizado(Object o) {
		return o instanceof PlanAmortizacionOperacionPorcentualFormImp;
	}

	/**
	 * Realiza el casting. Método creado para solucionar errores checkstyle.
	 * @param o Objeto
	 * @return PlanAmortizacionOperacionPorcentualFormImp.
	 */
	private PlanAmortizacionOperacionPorcentualFormImp cast(
			Object o) {
		return (PlanAmortizacionOperacionPorcentualFormImp) o;
	}

	/** GETTERS Y SETTERS **/

	/**
	 * Get RegeneraCuadroEventosHelper.
	 * @return RegeneraCuadroEventosHelper.
	 */
	public RegeneraCuadroEventosHelper getRegHelper() {
		return helper;
	}

	/**
	 * Set RegeneraCuadroEventosHelper.
	 * @param regeneraCEJDBC
	 *                RegeneraCuadroEventosHelper.
	 */
	public void setRegeneraCuadroEventosHelper(
			RegeneraCuadroEventosHelper regeneraCEJDBC) {
		this.helper = regeneraCEJDBC;
	}

	/**
	 * Get OperacionJDBC.
	 * @return OperacionJDBC.
	 */
	public OperacionJDBC getOperacionJDBC() {
		return operacionJDBC;
	}

	/**
	 * Set OperacionJDBC.
	 * @param operacionJDBC
	 *                OperacionJDBC.
	 */
	public void setOperacionJDBC(OperacionJDBC operacionJDBC) {
		this.operacionJDBC = operacionJDBC;
	}

	/**
	 * Recuperación del dataSource.
	 * @return dataSource.
	 */
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * Método encargado de actualizar la fecha de última regeneración para
	 * contabilidad.
	 * @param operacion
	 *                Operación recalculada.
	 * @param fechaEjecucion
	 *                fecha de última regeneración.
	 * @throws Exception
	 *                 En caso de error al actualizar la fecha.
	 */
	public void actualizaFechaContableRenovacionTipos(
			Operacion operacion, Date fechaEjecucion)
			throws Exception {

		Long idOperacion = operacion.getId();

		helper.setFechaInterfazContable(
				idOperacion, fechaEjecucion);

	}

	/**
	 * Recupera el total dispuesto a partir de una lista de eventos.
	 * @param eventos
	 *                lista de eventos.
	 * @return total dispuesto.
	 */
	public BigDecimal getTotalDispuesto(List<EventoManual> eventos) {
		BigDecimal total = BigDecimal.ZERO;

		for (EventoManual evento : eventos) {
			if (evento instanceof DisposicionOperacion) {
				total = total.add(evento.getImporte()
						.getCantidad());
			}
		}

		return total;
	}

	//INI ICO-68057
	public List<Evento> getEventosADFLiqDisposicion (Operacion operacion, List<Evento> evs, Date fecha) {
		List <Evento> listaEventos = new ArrayList<Evento>();
		for (Evento evento : evs) {
			if ((evento instanceof LiquidacionInteresesAutomaticaDisposicion) && (evento.getFechaEvento().equals(fecha))) {
				listaEventos.add(evento);
				
			}
		}
		return listaEventos;
	}
	//FIN ICO-68057
	
	//ICO-127268

	protected boolean contienePlanAmortizacionOperacionBase(List<PlanEvento> planesEjecucion) {
	    return planesEjecucion != null &&
	           !planesEjecucion.isEmpty() &&
	           planesEjecucion.stream().anyMatch(PlanAmortizacionOperacionBase.class::isInstance);
	}
	
	
	public boolean contieneEventoManualEELL(Operacion op) {
		if (TipoOperacionActivoEnum.EL.getCodigo().equals(op.getTipoOperacion()) && !op.getEventosManuales().isEmpty()) {
			Set<EventoManual> eventos = op.getEventosManuales();
			
			for (EventoManual evento : eventos) {
				if (((EventoImp) evento).getDiscriminador() == SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador() ||
						((EventoImp) evento).getDiscriminador() == SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador() ||
						((EventoImp) evento).getDiscriminador() == SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador() ||
						((EventoImp) evento).getDiscriminador() == SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador() ||
						((EventoImp) evento).getDiscriminador() == SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador() ||
						((EventoImp) evento).getDiscriminador() == SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador()) {
					return true;
				}
			}
		}
		return false;
	}
}