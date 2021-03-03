package pkg

import (
	"build-booster/common"
	"build-booster/common/blog"
	"build-booster/common/encrypt"
	"build-booster/common/http/httpserver"
	"build-booster/gateway/config"
	"build-booster/gateway/pkg/api"
	_ "build-booster/gateway/pkg/api/v1"
	"build-booster/gateway/pkg/register-discover"
	"build-booster/server/pkg/engine"
	"build-booster/server/pkg/engine/apisjob"
	"build-booster/server/pkg/engine/distcc"
	"build-booster/server/pkg/engine/disttask"
	"build-booster/server/pkg/engine/fastbuild"
)

type GatewayServer struct {
	conf       *config.GatewayConfig
	httpServer *httpserver.HTTPServer
	rd         register_discover.RegisterDiscover
}

func NewGatewayServer(conf *config.GatewayConfig) (*GatewayServer, error) {
	s := &GatewayServer{conf: conf}

	// Http server
	s.httpServer = httpserver.NewHTTPServer(s.conf.Port, s.conf.Address, "")
	if s.conf.ServerCert.IsSSL {
		s.httpServer.SetSSL(
			s.conf.ServerCert.CAFile, s.conf.ServerCert.CertFile, s.conf.ServerCert.KeyFile, s.conf.ServerCert.CertPwd)
	}

	return s, nil
}

func (dcs *GatewayServer) initHTTPServer() error {
	api.Rd = dcs.rd

	// Api v1
	return dcs.httpServer.RegisterWebServer(api.PathV1, nil, api.GetAPIV1Action())
}

func (dcs *GatewayServer) initDistCCResource() error {
	if !dcs.conf.DistCCMySQL.Enable {
		return nil
	}

	a := api.GetDistCCServerAPIResource()
	a.Conf = dcs.conf

	pwd, err := encrypt.DesDecryptFromBase([]byte(dcs.conf.DistCCMySQL.MySQLPwd))
	if err != nil {
		return err
	}
	a.MySQL, err = distcc.NewMySQL(engine.MySQLConf{
		MySQLStorage:  dcs.conf.DistCCMySQL.MySQLStorage,
		MySQLDatabase: dcs.conf.DistCCMySQL.MySQLDatabase,
		MySQLUser:     dcs.conf.DistCCMySQL.MySQLUser,
		MySQLPwd:      string(pwd),
		MySQLDebug:    dcs.conf.DistCCMySQL.Debug,
	})
	if err != nil {
		return err
	}

	if err := api.InitActionsFunc(); err != nil {
		return err
	}

	// Init routes in actions
	a.InitActions()
	blog.Infof("success to enable gateway for distcc mysql")
	return nil
}

func (dcs *GatewayServer) initFBResource() error {
	if !dcs.conf.FastBuildMySQL.Enable {
		return nil
	}

	a := api.GetFBServerAPIResource()
	a.Conf = dcs.conf

	pwd, err := encrypt.DesDecryptFromBase([]byte(dcs.conf.FastBuildMySQL.MySQLPwd))
	if err != nil {
		return err
	}
	a.MySQL, err = fastbuild.NewMySQL(engine.MySQLConf{
		MySQLStorage:  dcs.conf.FastBuildMySQL.MySQLStorage,
		MySQLDatabase: dcs.conf.FastBuildMySQL.MySQLDatabase,
		MySQLUser:     dcs.conf.FastBuildMySQL.MySQLUser,
		MySQLPwd:      string(pwd),
		MySQLDebug:    dcs.conf.FastBuildMySQL.Debug,
	})
	if err != nil {
		return err
	}

	if err := api.InitActionsFunc(); err != nil {
		return err
	}

	// Init routes in actions
	a.InitActions()
	blog.Infof("success to enable gateway for fastbuild mysql")
	return nil
}

func (dcs *GatewayServer) initAPISJobResource() error {
	if !dcs.conf.ApisJobMySQL.Enable {
		return nil
	}

	a := api.GetXNAPISServerAPIResource()
	a.Conf = dcs.conf

	pwd, err := encrypt.DesDecryptFromBase([]byte(dcs.conf.ApisJobMySQL.MySQLPwd))
	if err != nil {
		return err
	}
	a.MySQL, err = apisjob.NewMySQL(engine.MySQLConf{
		MySQLStorage:  dcs.conf.ApisJobMySQL.MySQLStorage,
		MySQLDatabase: dcs.conf.ApisJobMySQL.MySQLDatabase,
		MySQLUser:     dcs.conf.ApisJobMySQL.MySQLUser,
		MySQLPwd:      string(pwd),
		MySQLDebug:    dcs.conf.ApisJobMySQL.Debug,
	})
	if err != nil {
		return err
	}

	if err := api.InitActionsFunc(); err != nil {
		return err
	}

	// Init routes in actions
	a.InitActions()
	blog.Infof("success to enable gateway for apisjob mysql")
	return nil
}

func (dcs *GatewayServer) initDistTaskResource() error {
	if !dcs.conf.DistTaskMySQL.Enable {
		return nil
	}

	a := api.GetDistTaskServerAPIResource()
	a.Conf = dcs.conf

	pwd, err := encrypt.DesDecryptFromBase([]byte(dcs.conf.DistTaskMySQL.MySQLPwd))
	if err != nil {
		return err
	}
	a.MySQL, err = disttask.NewMySQL(engine.MySQLConf{
		MySQLStorage:  dcs.conf.DistTaskMySQL.MySQLStorage,
		MySQLDatabase: dcs.conf.DistTaskMySQL.MySQLDatabase,
		MySQLUser:     dcs.conf.DistTaskMySQL.MySQLUser,
		MySQLPwd:      string(pwd),
		MySQLDebug:    dcs.conf.DistTaskMySQL.Debug,
	})
	if err != nil {
		return err
	}

	if err := api.InitActionsFunc(); err != nil {
		return err
	}

	// Init routes in actions
	a.InitActions()
	blog.Infof("success to enable gateway for disttask mysql")
	return nil
}

func (dcs *GatewayServer) Start() error {
	var err error
	if dcs.rd, err = register_discover.NewRegisterDiscover(dcs.conf); err != nil {
		blog.Errorf("get new register discover failed: %v", err)
		return err
	}

	if err = dcs.rd.Run(); err != nil {
		blog.Errorf("get register discover event chan failed: %v", err)
		return err
	}

	// init distCC server related resources
	if err = dcs.initDistCCResource(); err != nil {
		return err
	}

	// init fb server related resources
	if err = dcs.initFBResource(); err != nil {
		return err
	}

	// init apis job server related resources
	if err = dcs.initAPISJobResource(); err != nil {
		return err
	}

	// init disttask server related resources
	if err = dcs.initDistTaskResource(); err != nil {
		return err
	}

	// register all APIs
	if err = dcs.initHTTPServer(); err != nil {
		return err
	}

	return dcs.httpServer.ListenAndServe()
}

// Run brings up the server
func Run(conf *config.GatewayConfig) error {
	if err := common.SavePid(conf.ProcessConfig); err != nil {
		blog.Errorf("save pid failed: %v", err)
		return err
	}

	server, err := NewGatewayServer(conf)
	if err != nil {
		blog.Errorf("init proxy server failed: %v", err)
		return err
	}

	return server.Start()
}
